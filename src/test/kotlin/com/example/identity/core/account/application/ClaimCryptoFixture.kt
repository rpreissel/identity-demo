package com.example.identity.core.account.application

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.MasterKeyId
import com.example.identity.contract.tool_api.kms.AccountSealing
import com.example.identity.core.account.AccountDataCipher
import com.example.identity.core.account.infrastructure.Account
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.AccountRepository
import com.example.identity.core.account.infrastructure.ClaimBatchKey
import com.example.identity.core.account.infrastructure.ClaimBatchKeyRepository
import com.example.identity.core.account.infrastructure.MasterKey
import com.example.identity.core.account.infrastructure.MasterKeyRepository
import com.example.identity.simulation.kms.InMemoryKms
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * The key hierarchy without a database: the in-memory KMS, master keys and batch keys kept in
 * lists. [accountRepository] may be the spec's own mock, so its other stubs stay in one place.
 */
class ClaimCryptoFixture(val accountRepository: AccountRepository = mockk(), encryptionEnabled: Boolean = true) {
    val batchKeyRepository = mockk<ClaimBatchKeyRepository>()
    val masterKeyRepository = mockk<MasterKeyRepository>()
    val batchKeys = mutableListOf<ClaimBatchKey>()
    val masterKeys = mutableListOf<MasterKey>()
    val kms = InMemoryKms()
    val wrapper = KmsKekWrapper(kms.transit)
    val envelopes = Envelopes(encryptionEnabled)
    val crypto = ClaimCrypto(masterKeyRepository, batchKeyRepository, wrapper, envelopes, TEST_CLOCK)
    /** The port a method module seals through. */
    val sealing: AccountSealing = AccountDataCipher(crypto)
    private val accounts = mutableMapOf<AccountId, Account>()

    init {
        every { accountRepository.findAccount(any()) } answers { accounts[accountIdArg(firstArg())] }
        every { masterKeyRepository.save(any<MasterKey>()) } answers {
            firstArg<MasterKey>().also { key -> masterKeys.removeIf { it.keyUuid == key.keyUuid }; masterKeys.add(key) }
        }
        every { masterKeyRepository.findById(any()) } answers { Optional.ofNullable(masterKeys.firstOrNull { it.keyUuid == firstArg<UUID>() }) }
        // A default interface method: MockK answers it only when stubbed.
        every { masterKeyRepository.findKey(any()) } answers { masterKeys.firstOrNull { it.keyUuid == masterKeyIdArg(firstArg()) } }
        every { masterKeyRepository.findByAccountIdAndPrimaryTrue(any()) } answers {
            masterKeys.firstOrNull { it.accountId == accountIdArg(firstArg()) && it.primary }
        }
        every { masterKeyRepository.findByAccountId(any()) } answers { masterKeys.filter { it.accountId == accountIdArg(firstArg()) } }
        every { batchKeyRepository.save(any<ClaimBatchKey>()) } answers { firstArg<ClaimBatchKey>().also(batchKeys::add) }
        every { batchKeyRepository.findByClaimBatchIdAndAccountId(any(), any()) } answers {
            batchKeys.firstOrNull { it.claimBatchId == firstArg() && it.accountId == accountIdArg(secondArg()) }
        }
        every { batchKeyRepository.deleteByClaimBatchId(any()) } answers { if (batchKeys.removeIf { it.claimBatchId == firstArg() }) 1 else 0 }
    }

    /** An account with its own primary master key, findable by id. */
    fun account(accountId: AccountId): Account =
        accounts.getOrPut(accountId) { Account(createdAt = TEST_NOW).apply { id = accountId.value }.also { crypto.createPrimaryKey(accountId) } }

    /** A journey's key, owned by nobody yet. */
    fun newKey(): MasterKeyId = crypto.newPendingKey()

    /** An encrypted claim row of [accountId] in a batch of its own, as the ledger would write it. */
    fun claim(
        accountId: AccountId, type: AttributeType, value: String, source: ClaimSource,
        establishedAt: Instant = TEST_NOW, authMethodId: UUID? = null, establishedAcr: String = "loa2",
    ): AccountClaim {
        account(accountId)
        val cipher = crypto.open(accountId)
        val batch = cipher.newBatch(expiresAt = null, now = establishedAt)
        return AccountClaim(
            accountId = accountId, attributeType = type, encryptedValue = batch.encrypt(value), valueDigest = cipher.digest(type, value),
            claimBatchId = batch.id, claimSource = source.value, establishedAcr = establishedAcr, authMethodId = authMethodId, establishedAt = establishedAt
        )
    }

    fun valueOf(claim: AccountClaim): String? = crypto.open(checkNotNull(claim.accountId)).decrypt(claim)

    /** MockK hands a value-class argument over unboxed. */
    private fun accountIdArg(arg: Any): AccountId = arg as? AccountId ?: AccountId(arg as Long)

    private fun masterKeyIdArg(arg: Any): UUID = (arg as? MasterKeyId)?.value ?: arg as UUID

}
