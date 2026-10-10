package com.example.identity.core.account

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.MasterKeyId
import com.example.identity.core.account.application.ClaimCrypto
import com.example.identity.core.account.infrastructure.MasterKeyRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * The master keys a tool seals its secrets under (ADR-55): the account's primary key, or the key of
 * a journey that has no account yet. The account adopts a journey's key when the journey binds it;
 * a key no account adopted is swept once no channel can name it.
 */
@Service
class JourneyKeys(private val claimCrypto: ClaimCrypto, private val masterKeyRepository: MasterKeyRepository) {

    /** A master key for a journey that has no account yet; the account adopts it later. */
    @Transactional
    fun newJourneyKey(): MasterKeyId = claimCrypto.newPendingKey()

    /** The key a tool on this account seals under: the account's primary key. */
    @Transactional(readOnly = true)
    fun masterKeyOf(accountId: AccountId): MasterKeyId = claimCrypto.primaryKeyOf(accountId)

    /**
     * The journey's key joins the account; what was sealed under it before the account was known
     * stays readable. `false` if the key is gone or another account's: then nothing of this account
     * lies under it, and the channel should forget it.
     */
    @Transactional
    fun adoptJourneyKey(accountId: AccountId, journeyKey: MasterKeyId): Boolean = claimCrypto.adopt(accountId, journeyKey)

    /** Whether a journey may still seal under [journeyKey]: it exists and no account owns it. */
    @Transactional(readOnly = true)
    fun isPendingJourneyKey(journeyKey: MasterKeyId): Boolean = claimCrypto.isPending(journeyKey)

    /** Keys no account adopted and no channel can still name - the journey ended without one. */
    @Transactional
    fun deleteJourneyKeysCreatedBefore(cutoff: Instant): Int = masterKeyRepository.deletePendingCreatedBefore(cutoff)
}
