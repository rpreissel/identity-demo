package com.example.identity.core.account.application

import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.AccountDeleted
import com.example.identity.core.account.domain.normalizeClaimValue
import org.springframework.context.event.EventListener
import com.example.identity.contract.tool_api.ids.MasterKeyId
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.ClaimBatchKey
import com.example.identity.core.account.infrastructure.ClaimBatchKeyRepository
import com.example.identity.core.account.infrastructure.MasterKey
import com.example.identity.core.account.infrastructure.MasterKeyRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * The key hierarchy of the claim log (ADR-52): the KEK wraps the master keys ([MasterKey], one
 * primary per account, ADR-55), the master key wraps one data key per batch ([ClaimBatchKey]), the
 * data key encrypts the values. Equality within an account uses an HMAC under the master key, so
 * dedup and retraction stay SQL. [open] unwraps the master key once per operation; with a KMS
 * behind [MasterKeyWrapper] that is the only call that leaves the process. Three subkeys are
 * derived from the master key: wrapping, digest and sealing, so no key serves two purposes.
 */
@Component
class ClaimCrypto(
    private val masterKeys: MasterKeyRepository,
    private val batchKeys: ClaimBatchKeyRepository,
    private val wrapper: MasterKeyWrapper,
    private val envelopes: Envelopes,
    private val clock: Clock,
    /** How long an unwrapped master key stays in this instance; the rate toward the KMS follows from it (ADR-52). */
    @Value("\${identity.secrets.master-key-cache-ttl:PT5M}") private val cacheTtl: Duration = Duration.ofMinutes(5),
    @Value("\${identity.secrets.master-key-cache-size:100000}") private val cacheSize: Int = 100_000,
) {
    private class Cached(val masterKey: ByteArray, val expiresAt: Instant)

    // Bounded and short-lived: a master key leaves memory after cacheTtl, and the map never grows past cacheSize.
    private val cache = object : java.util.LinkedHashMap<MasterKeyId, Cached>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MasterKeyId, Cached>): Boolean = size > cacheSize
    }

    /**
     * A master key nobody owns yet (ADR-55): a journey seals under it until its account exists. Rows
     * are committed on their own, so a sealing module can read the key back in any transaction.
     */
    fun newPendingKey(): MasterKeyId = createKey(accountId = null, primary = false).let { checkNotNull(it.keyId) }

    /** The account's first key: claims and tokens live under it. */
    fun createPrimaryKey(accountId: AccountId): MasterKeyId = createKey(accountId, primary = true).let { checkNotNull(it.keyId) }

    /**
     * The account takes the journey's key: as its primary key if it has none yet, otherwise as a
     * further key that keeps readable what was sealed under it before the account was known.
     * `false` if the key is gone or another account's - then it is not this account's key.
     */
    fun adopt(accountId: AccountId, keyId: MasterKeyId): Boolean {
        val key = masterKeys.findKey(keyId) ?: return false
        // An earlier binding of this channel adopted it already.
        if (key.accountId != null) return key.accountId == accountId
        key.accountId = accountId
        key.primary = masterKeys.findByAccountIdAndPrimaryTrue(accountId) == null
        masterKeys.save(key)
        return true
    }

    /** Whether [keyId] is a key nobody owns yet, so a journey may still seal under it. */
    fun isPending(keyId: MasterKeyId): Boolean = masterKeys.findKey(keyId)?.let { it.accountId == null } ?: false

    /** The id of the account's primary key, for a tool context on a channel that has an account. */
    fun primaryKeyOf(accountId: AccountId): MasterKeyId =
        checkNotNull(masterKeys.findByAccountIdAndPrimaryTrue(accountId)?.keyId) { "account $accountId has no master key" }

    private fun createKey(accountId: AccountId?, primary: Boolean): MasterKey {
        val wrapped = wrapper.wrap(MASTER_KEY_PURPOSE, AesGcm.newKey())
        return masterKeys.save(
            MasterKey(keyUuid = UUID.randomUUID(), accountId = accountId, primary = primary, wrappedMasterKey = wrapped.bytes, kekVersion = wrapped.kekVersion, createdAt = clock.instant())
        )
    }

    /** The account's keys for one operation, under its primary key; unwrapped data keys are kept for its duration only. */
    fun open(accountId: AccountId): AccountCipher = openKey(primaryKeyOf(accountId))

    /** Any master key by id - the account's or a journey's pending one (ADR-55). */
    fun openKey(keyId: MasterKeyId): AccountCipher = AccountCipher(keyId, masterKeyOf(keyId))

    private fun masterKeyOf(keyId: MasterKeyId): ByteArray {
        val now = clock.instant()
        synchronized(cache) { cache[keyId]?.takeIf { it.expiresAt.isAfter(now) }?.let { return it.masterKey } }
        val stored = masterKeys.findKey(keyId) ?: error("master key $keyId not found")
        val masterKey = wrapper.unwrap(MASTER_KEY_PURPOSE, WrappedKey(checkNotNull(stored.wrappedMasterKey), checkNotNull(stored.kekVersion)))
        synchronized(cache) { cache[keyId] = Cached(masterKey, now + cacheTtl) }
        return masterKey
    }

    /** A deleted account's keys leave this instance at once, not after the cache period. */
    @EventListener(AccountDeleted::class)
    fun forget(event: AccountDeleted) {
        synchronized(cache) { cache.keys.removeAll(event.masterKeyIds.toSet()) }
    }

    fun deleteBatch(claimBatchId: UUID) {
        batchKeys.deleteByClaimBatchId(claimBatchId)
    }

    /** Opened under one master key; batch keys belong to the account that key is primary for. */
    inner class AccountCipher(private val keyId: MasterKeyId, masterKey: ByteArray) {
        // Three subkeys, so wrapping, digest and sealing never share key material.
        private val wrapKey = SecretKeySpec(hmac(masterKey, "wrap".toByteArray()), "AES")
        private val digestKey = SecretKeySpec(hmac(masterKey, "digest".toByteArray()), HMAC)
        private val sealKey = SecretKeySpec(hmac(masterKey, "seal".toByteArray()), "AES")
        /** `null` remembers that the batch key is gone, so an erased batch costs one lookup per operation. */
        private val dataKeys = mutableMapOf<UUID, SecretKey?>()
        // Batch keys belong to the account; looked up once per operation.
        private val ownerId: AccountId by lazy { accountIdOf(keyId) }

        /** Encrypts [plaintext] directly under the account's key, bound to [purpose]; for data that lives and dies with the account. */
        private val keyRef = Envelopes.masterKey(keyId)

        fun seal(purpose: String, plaintext: ByteArray): ByteArray = envelopes.seal(sealKey, keyRef, purposeAad(purpose), plaintext)

        fun open(purpose: String, sealed: ByteArray): ByteArray = envelopes.open(sealKey, keyRef, purposeAad(purpose), sealed)

        private fun purposeAad(purpose: String) = "key:$keyId:$purpose".toByteArray()

        /**
         * The stored equality form of [value]: normalized, then keyed-hashed. In the demo it is the
         * normalized value itself behind the key's header (ADR-55): readable, and still equal for
         * equal values. Dedup and retraction compare this column in SQL, so the mode is fixed per
         * database, never switched on data.
         */
        fun digest(type: AttributeType, value: String): String {
            val normalized = checkNotNull(normalizeClaimValue(type, value))
            return if (envelopes.encryptionEnabled) HexFormat.of().formatHex(hmac(digestKey, "${type.wireName}\u001F$normalized".toByteArray()))
            else envelopes.readableDigest(keyRef, "${type.wireName}=$normalized")
        }

        /** Creates and stores the data key of a new batch. */
        fun newBatch(expiresAt: Instant?, now: Instant): OpenBatch {
            val id = UUID.randomUUID()
            val dek = AesGcm.newKey()
            batchKeys.save(
                // A key is wrapped in every mode; only values follow the demo switch.
                ClaimBatchKey(claimBatchId = id, accountId = ownerId, wrappedDek = envelopes.seal(wrapKey, keyRef, batchAad(id), dek, encrypt = true), expiresAt = expiresAt, createdAt = now)
            )
            val key = SecretKeySpec(dek, "AES").also { dataKeys[id] = it }
            return OpenBatch(id, key)
        }

        /** The value of [claim], or `null` once its batch key is deleted (cryptographic erasure). */
        fun decrypt(claim: AccountClaim): String? {
            val batchId = checkNotNull(claim.claimBatchId) { "claim ${claim.id} without a batch" }
            if (!dataKeys.containsKey(batchId)) {
                dataKeys[batchId] = batchKeys.findByClaimBatchIdAndAccountId(batchId, ownerId)
                    ?.let { SecretKeySpec(envelopes.open(wrapKey, keyRef, batchAad(batchId), checkNotNull(it.wrappedDek)), "AES") }
            }
            val key = dataKeys[batchId] ?: return null
            return String(envelopes.open(key, Envelopes.batch(batchId), batchAad(batchId), checkNotNull(claim.encryptedValue)))
        }

        private fun batchAad(batchId: UUID) = "key:$keyId:batch:$batchId".toByteArray()

        inner class OpenBatch(val id: UUID, private val key: SecretKey) {
            fun encrypt(value: String): ByteArray = envelopes.seal(key, Envelopes.batch(id), batchAad(id), value.toByteArray())
        }
    }

    private fun accountIdOf(keyId: MasterKeyId): AccountId =
        checkNotNull(masterKeys.findKey(keyId)?.accountId) { "master key $keyId belongs to no account yet" }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray = hmac(SecretKeySpec(key, HMAC), input)

    private fun hmac(key: SecretKey, input: ByteArray): ByteArray = Mac.getInstance(HMAC).apply { init(key) }.doFinal(input)

    private companion object {
        const val HMAC = "HmacSHA256"
        /** Bound into the wrapping, so a wrapped data key of the orchestrator cannot pass as a master key. */
        const val MASTER_KEY_PURPOSE = "account-master-key"
    }
}
