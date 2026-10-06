package com.example.identity.core.account.application

import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.domain.normalizeClaimValue
import com.example.identity.core.account.infrastructure.Account
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.AccountRepository
import com.example.identity.core.account.infrastructure.ClaimBatchKey
import com.example.identity.core.account.infrastructure.ClaimBatchKeyRepository
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component

/**
 * The key hierarchy of the claim log (ADR-52): the KEK wraps one master key per account
 * ([Account.wrappedMasterKey]), the master key wraps one data key per batch ([ClaimBatchKey]), the
 * data key encrypts the values. Equality within an account uses an HMAC under the master key, so
 * dedup and retraction stay SQL. [open] unwraps the master key once per operation; with a KMS
 * behind [MasterKeyWrapper] that is the only call that leaves the process. Three subkeys are
 * derived from the master key: wrapping, digest and sealing, so no key serves two purposes.
 */
@Component
class ClaimCrypto(
    private val accountRepository: AccountRepository,
    private val batchKeys: ClaimBatchKeyRepository,
    private val wrapper: MasterKeyWrapper,
) {
    /** Gives a new account its master key, before the row is saved: the column is not nullable. */
    fun assignMasterKey(account: Account) {
        val wrapped = wrapper.wrap(AesGcm.newKey())
        account.wrappedMasterKey = wrapped.bytes
        account.kekVersion = wrapped.kekVersion
    }

    /** The account's keys for one operation; unwrapped data keys are kept for its duration only. */
    fun open(accountId: AccountId): AccountCipher {
        val stored = accountRepository.findStoredMasterKey(accountId) ?: error("Account not found: $accountId")
        val masterKey = wrapper.unwrap(WrappedKey(stored.wrappedMasterKey, stored.kekVersion))
        return AccountCipher(accountId, masterKey)
    }

    fun deleteBatch(claimBatchId: UUID) {
        batchKeys.deleteByClaimBatchId(claimBatchId)
    }

    inner class AccountCipher(private val accountId: AccountId, masterKey: ByteArray) {
        // Three subkeys, so wrapping, digest and sealing never share key material.
        private val wrapKey = SecretKeySpec(hmac(masterKey, "wrap".toByteArray()), "AES")
        private val digestKey = SecretKeySpec(hmac(masterKey, "digest".toByteArray()), HMAC)
        private val sealKey = SecretKeySpec(hmac(masterKey, "seal".toByteArray()), "AES")
        /** `null` remembers that the batch key is gone, so an erased batch costs one lookup per operation. */
        private val dataKeys = mutableMapOf<UUID, SecretKey?>()

        /** Encrypts [plaintext] directly under the account's key, bound to [purpose]; for data that lives and dies with the account. */
        fun seal(purpose: String, plaintext: ByteArray): ByteArray = AesGcm.seal(sealKey, purposeAad(purpose), plaintext)

        fun open(purpose: String, sealed: ByteArray): ByteArray = AesGcm.open(sealKey, purposeAad(purpose), sealed)

        private fun purposeAad(purpose: String) = "account:${accountId.value}:$purpose".toByteArray()

        /** The stored equality form of [value]: normalized, then keyed-hashed. */
        fun digest(type: AttributeType, value: String): String {
            val normalized = checkNotNull(normalizeClaimValue(type, value))
            return HexFormat.of().formatHex(hmac(digestKey, "${type.wireName}\u001F$normalized".toByteArray()))
        }

        /** Creates and stores the data key of a new batch. */
        fun newBatch(expiresAt: Instant?, now: Instant): OpenBatch {
            val id = UUID.randomUUID()
            val dek = AesGcm.newKey()
            batchKeys.save(
                ClaimBatchKey(claimBatchId = id, accountId = accountId, wrappedDek = AesGcm.seal(wrapKey, batchAad(id), dek), expiresAt = expiresAt, createdAt = now)
            )
            val key = SecretKeySpec(dek, "AES").also { dataKeys[id] = it }
            return OpenBatch(id, key)
        }

        /** The value of [claim], or `null` once its batch key is deleted (cryptographic erasure). */
        fun decrypt(claim: AccountClaim): String? {
            val batchId = checkNotNull(claim.claimBatchId) { "claim ${claim.id} without a batch" }
            if (!dataKeys.containsKey(batchId)) {
                dataKeys[batchId] = batchKeys.findByClaimBatchIdAndAccountId(batchId, accountId)
                    ?.let { SecretKeySpec(AesGcm.open(wrapKey, batchAad(batchId), checkNotNull(it.wrappedDek)), "AES") }
            }
            val key = dataKeys[batchId] ?: return null
            return String(AesGcm.open(key, batchAad(batchId), checkNotNull(claim.encryptedValue)))
        }

        private fun batchAad(batchId: UUID) = "account:${accountId.value}:batch:$batchId".toByteArray()

        inner class OpenBatch(val id: UUID, private val key: SecretKey) {
            fun encrypt(value: String): ByteArray = AesGcm.seal(key, batchAad(id), value.toByteArray())
        }
    }

    private fun hmac(key: ByteArray, input: ByteArray): ByteArray = hmac(SecretKeySpec(key, HMAC), input)

    private fun hmac(key: SecretKey, input: ByteArray): ByteArray = Mac.getInstance(HMAC).apply { init(key) }.doFinal(input)

    private companion object {
        const val HMAC = "HmacSHA256"
    }
}
