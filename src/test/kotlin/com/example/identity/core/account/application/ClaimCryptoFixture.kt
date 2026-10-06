package com.example.identity.core.account.application

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.infrastructure.Account
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.AccountRepository
import com.example.identity.core.account.infrastructure.ClaimBatchKey
import com.example.identity.core.account.infrastructure.ClaimBatchKeyRepository
import com.example.identity.core.account.infrastructure.StoredMasterKey
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

/**
 * The key hierarchy without a database: a test KEK, accounts with a master key, batch keys kept in
 * a list. [accountRepository] may be the spec's own mock, so its other stubs stay in one place.
 */
class ClaimCryptoFixture(val accountRepository: AccountRepository = mockk()) {
    val batchKeyRepository = mockk<ClaimBatchKeyRepository>()
    val batchKeys = mutableListOf<ClaimBatchKey>()
    val wrapper = ConfiguredKekWrapper("test-kek-of-at-least-32-characters", "1", PreviousMasterKeks())
    val crypto = ClaimCrypto(accountRepository, batchKeyRepository, wrapper)
    private val accounts = mutableMapOf<AccountId, Account>()

    init {
        every { accountRepository.findStoredMasterKey(any()) } answers {
            accounts[accountIdArg(firstArg())]?.let { StoredMasterKey(it.wrappedMasterKey!!, it.kekVersion!!) }
        }
        every { batchKeyRepository.save(any<ClaimBatchKey>()) } answers { firstArg<ClaimBatchKey>().also(batchKeys::add) }
        every { batchKeyRepository.findByClaimBatchIdAndAccountId(any(), any()) } answers {
            batchKeys.firstOrNull { it.claimBatchId == firstArg() && it.accountId == accountIdArg(secondArg()) }
        }
        every { batchKeyRepository.deleteById(any()) } answers { batchKeys.removeIf { it.claimBatchId == firstArg() } }
    }

    /** An account with its own master key, findable by id. */
    fun account(accountId: AccountId): Account =
        accounts.getOrPut(accountId) { Account(createdAt = TEST_NOW).apply { id = accountId.value; crypto.assignMasterKey(this) } }

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
}
