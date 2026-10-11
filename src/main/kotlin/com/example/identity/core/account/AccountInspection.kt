package com.example.identity.core.account

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.application.AnchorRegistry
import com.example.identity.core.account.application.ClaimCrypto
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.AccountClaimRepository
import com.example.identity.core.account.infrastructure.AccountRetraction
import com.example.identity.core.account.infrastructure.AccountRetractionRepository
import com.example.identity.core.account.infrastructure.ChangeLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/** One anchor: a value the account is found by, unique across accounts. */
data class AnchorRecord(
    val attributeType: String,
    val value: String,
    val establishedAcr: String?,
    val establishedAt: Instant,
)

/** A withdrawn value (ADR-12). It carries no value, only what it matched. */
data class RetractionRecord(
    val id: Long,
    val attributeType: String,
    val source: String,
    val reason: String?,
    val retractedAt: Instant,
)

/**
 * One assertion of the claim log, retracted or not. [value] is null once its batch key is deleted
 * (cryptographic erasure, ADR-52); the row stays. [retraction] is the one that cancelled it, by the
 * same rule as `AccountClaimRepository.findEstablished`.
 */
data class ClaimRecord(
    val id: Long,
    val attributeType: String,
    val value: String?,
    val claimSource: String?,
    val establishedAcr: String?,
    /** The method instance whose enrollment asserted it; null for identification tools. */
    val authMethodId: String?,
    val establishedAt: Instant,
    val retraction: RetractionRecord?,
)

/** Everything the account module knows about one account, for the operator's account view. */
data class AccountInspectionRecord(
    val profile: AccountProfile,
    val createdAt: Instant?,
    val anchors: List<AnchorRecord>,
    val claims: List<ClaimRecord>,
    val retractions: List<RetractionRecord>,
    val changeLog: List<ChangeLogRecord>,
    val signIns: List<SignInRecord>,
)

/**
 * The operator's look at one account: the whole claim log with its retractions, the anchors, the
 * methods and both logs. Readers elsewhere see only what is established (`ClaimLedger`); this one
 * shows the history, so support can answer "where does this value come from, and why is it gone?".
 * It decrypts values; the admin view decides what of them to show.
 */
@Service
class AccountInspection(
    private val accountService: AccountService,
    private val anchorRegistry: AnchorRegistry,
    private val claimRepository: AccountClaimRepository,
    private val retractionRepository: AccountRetractionRepository,
    private val changeLogRepository: ChangeLogRepository,
    private val signInLog: SignInLog,
    private val claimCrypto: ClaimCrypto,
) {
    @Transactional(readOnly = true)
    fun inspect(accountId: AccountId): AccountInspectionRecord? {
        val profile = accountService.findAccount(accountId) ?: return null
        val retractions = retractionRepository.findByAccountIdOrderByRetractedAt(accountId)
        val claims = claimRepository.findByAccountIdOrderByEstablishedAt(accountId)
        val cipher = if (claims.isEmpty()) null else claimCrypto.open(accountId)
        return AccountInspectionRecord(
            profile = profile,
            createdAt = accountService.createdAt(accountId),
            anchors = anchorRegistry.anchorsOf(accountId).map {
                AnchorRecord(checkNotNull(it.attributeType).wireName, it.value.orEmpty(), it.establishedAcr, checkNotNull(it.establishedAt))
            },
            claims = claims.map { claim ->
                ClaimRecord(
                    id = checkNotNull(claim.id),
                    attributeType = checkNotNull(claim.attributeType).wireName,
                    value = cipher?.decrypt(claim),
                    claimSource = claim.claimSource,
                    establishedAcr = claim.establishedAcr,
                    authMethodId = claim.authMethodId?.toString(),
                    establishedAt = checkNotNull(claim.establishedAt),
                    retraction = retractions.firstOrNull { it.cancels(claim) }?.toRecord(),
                )
            },
            retractions = retractions.map { it.toRecord() },
            changeLog = changeLogRepository.findByAccountIdOrderByOccurredAt(accountId).map {
                ChangeLogRecord(checkNotNull(it.accountId), it.changeType.name, it.subject, it.acr, it.details.orEmpty(), it.occurredAt)
            },
            signIns = signInLog.of(accountId),
        )
    }

    /** The rule of `AccountClaimRepository.findEstablished`, for one pair. */
    private fun AccountRetraction.cancels(claim: AccountClaim): Boolean =
        attributeType == claim.attributeType &&
            valueDigest == claim.valueDigest &&
            (claimBatchId == null || claimBatchId == claim.claimBatchId) &&
            !checkNotNull(retractedAt).isBefore(claim.establishedAt)

    private fun AccountRetraction.toRecord() = RetractionRecord(
        id = checkNotNull(id),
        attributeType = checkNotNull(attributeType).wireName,
        source = checkNotNull(retractionSource).name,
        reason = reason,
        retractedAt = checkNotNull(retractedAt),
    )
}
