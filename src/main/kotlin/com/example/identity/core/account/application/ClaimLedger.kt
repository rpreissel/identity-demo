package com.example.identity.core.account.application

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.infrastructure.strongestEstablished
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.AccountClaimRepository
import com.example.identity.core.account.infrastructure.AccountRetraction
import com.example.identity.core.account.infrastructure.AccountRetractionRepository
import com.example.identity.core.account.infrastructure.ClaimBatchKey
import com.example.identity.core.account.domain.ClaimKey
import com.example.identity.core.account.RetractionSource
import com.example.identity.contract.tool_api.claims.AttributeAuthority
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.ClaimTrust
import com.example.identity.contract.tool_api.claims.claimTrust
import com.example.identity.contract.tool_api.claims.validateValue
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The account's claim log (docs/02-domaenenmodell.md #6): what was asserted, by which source and
 * method, and what was withdrawn (ADR-12). Only appends and reads; `AccountService` decides locking
 * and order. Every withdrawal goes through [retract], so none escapes the change log (ADR-39).
 * Values are stored encrypted per batch (ADR-52); a batch whose claims are all withdrawn or whose
 * retention ended loses its data key, and with it its values.
 */
@Component
class ClaimLedger(
    private val accountClaimRepository: AccountClaimRepository,
    private val accountRetractionRepository: AccountRetractionRepository,
    private val changeLog: ChangeLog,
    private val crypto: ClaimCrypto,
    private val retentionPolicy: ClaimRetentionPolicy,
    private val clock: Clock,
) {

    /**
     * Appends the [claims] of one completed tool run, at most one per [AttributeType]; an already
     * logged [ClaimKey] adds no row. [provenAcr] caps what a claim establishes (ADR-5). One batch,
     * and so one data key, per retention rule among the new claims.
     *
     * @return each claim with its establishing instant, which the following anchor write reuses.
     */
    fun append(accountId: AccountId, claims: List<Claim>, provenAcr: AcrLevel, authMethodId: UUID?): List<Pair<Claim, Instant>> {
        val seen = mutableSetOf<AttributeType>()
        claims.forEach { claim ->
            claim.validateValue()
            check(seen.add(claim.attributeType)) {
                "recordClaims($accountId): more than one claim for ${claim.attributeType.wireName}"
            }
        }
        val cipher = crypto.open(accountId)
        val logged = accountClaimRepository.findEstablished(accountId)
            .map { claim ->
                ClaimKey(
                    checkNotNull(claim.attributeType),
                    checkNotNull(claim.valueDigest),
                    checkNotNull(claim.claimSource),
                    claim.authMethodId
                )
            }
            .toMutableSet()
        val establishedAt = clock.instant()
        val new = claims.mapNotNull { claim ->
            val digest = cipher.digest(claim.attributeType, claim.value)
            if (logged.add(ClaimKey(claim.attributeType, digest, claim.source.value, authMethodId))) claim to digest else null
        }
        new.groupBy { (claim, _) -> retentionPolicy.retentionOf(claim.attributeType) }.forEach { (retention, group) ->
            val batch = cipher.newBatch(expiresAt = retention?.let { establishedAt + it }, now = establishedAt)
            group.forEach { (claim, digest) ->
                accountClaimRepository.save(
                    AccountClaim(
                        accountId = accountId,
                        attributeType = claim.attributeType,
                        encryptedValue = batch.encrypt(claim.value),
                        valueDigest = digest,
                        claimBatchId = batch.id,
                        claimSource = claim.source.value,
                        // What was actually proven, not what the tool can reach at most (ADR-5).
                        establishedAcr = (claim.establishedAcr?.let { AcrLevel.min(it, provenAcr) } ?: provenAcr).value,
                        authMethodId = authMethodId,
                        establishedAt = establishedAt
                    )
                )
            }
        }
        return claims.map { it to establishedAt }
    }

    /** Assertions minus retractions - the one view every reader uses. Values stay encrypted here. */
    fun established(accountId: AccountId): List<AccountClaim> = accountClaimRepository.findEstablished(accountId)

    /** The established claims with their values, oldest first - for replaying them onto another account. */
    fun establishedWithValues(accountId: AccountId): List<Pair<AccountClaim, String>> {
        val cipher = crypto.open(accountId)
        return established(accountId).sortedBy { it.establishedAt }.mapNotNull { claim -> cipher.decrypt(claim)?.let { claim to it } }
    }

    /** The established VALUES for [types], strongest assertion per attribute. */
    fun establishedValues(accountId: AccountId, types: Set<AttributeType>): Map<AttributeType, String> =
        decrypt(accountId, established(accountId).strongestEstablished(types))

    /** The same, counting only sources that PROVE the value - self-reported ones never do. */
    fun provenValues(accountId: AccountId, types: Set<AttributeType>): Map<AttributeType, String> =
        decrypt(
            accountId,
            established(accountId)
                .filter { ClaimSource(it.claimSource.orEmpty()).claimTrust.rank >= ClaimTrust.PROVEN.rank }
                .strongestEstablished(types)
        )

    private fun decrypt(accountId: AccountId, strongest: Map<AttributeType, AccountClaim>): Map<AttributeType, String> {
        if (strongest.isEmpty()) return emptyMap()
        val cipher = crypto.open(accountId)
        return strongest.mapNotNull { (type, claim) -> cipher.decrypt(claim)?.let { type to it } }.toMap()
    }

    /** Highest [ClaimTrust] per established attribute. Metadata only, so nothing is decrypted. */
    fun establishedTrust(accountId: AccountId): Map<AttributeType, ClaimTrust> =
        established(accountId)
            .mapNotNull { claim ->
                val type = claim.attributeType ?: return@mapNotNull null
                val source = claim.claimSource?.let(::ClaimSource) ?: return@mapNotNull null
                type to source.claimTrust
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, levels) -> levels.maxBy { it.rank } }

    /** What method instance [instanceId] asserted that belongs to its method module, as (type, digest). */
    fun ownedBy(accountId: AccountId, instanceId: UUID): Set<Pair<AttributeType, String?>> =
        accountClaimRepository.findByAuthMethodId(instanceId)
            .filter { it.accountId == accountId && it.attributeType?.authority == AttributeAuthority.MethodModule }
            .mapNotNull { claim -> claim.attributeType?.let { it to claim.valueDigest } }
            .toSet()

    /**
     * Withdraws every established value of [attributeType] - one retraction row per distinct value.
     * @return true if something was established and is now withdrawn.
     */
    fun retractEstablished(accountId: AccountId, attributeType: AttributeType, retractionSource: RetractionSource, reason: String?, at: Instant): Boolean {
        val established = established(accountId)
            .filter { it.attributeType == attributeType }
            .map { it.valueDigest }
            .distinct()
        established.forEach { retract(accountId, attributeType, it, retractionSource, reason, at) }
        return established.isNotEmpty()
    }

    /** [retract] for a caller that holds the value, not its digest, e.g. the anchor being replaced. */
    fun retractValue(accountId: AccountId, type: AttributeType, value: String, retractionSource: RetractionSource, reason: String?, at: Instant) =
        retract(accountId, type, crypto.open(accountId).digest(type, value), retractionSource, reason, at)

    /**
     * One withdrawal, logged in the change log. A batch left without any established claim loses
     * its data key at once: the withdrawn values become unreadable, their rows stay as metadata.
     */
    fun retract(accountId: AccountId, type: AttributeType, valueDigest: String?, retractionSource: RetractionSource, reason: String?, at: Instant) {
        changeLog.attributeRetracted(accountId, type.wireName, retractionSource = retractionSource.name, reason = reason, at = at)
        accountRetractionRepository.save(
            AccountRetraction(
                accountId = accountId,
                attributeType = type,
                valueDigest = valueDigest,
                retractionSource = retractionSource,
                reason = reason,
                retractedAt = at
            )
        )
        val touched = accountClaimRepository.findByAccountIdAndAttributeTypeAndValueDigest(accountId, type, valueDigest)
            .mapNotNull { it.claimBatchId }.toSet()
        if (touched.isEmpty()) return
        val live = established(accountId).mapNotNull { it.claimBatchId }.toSet()
        (touched - live).forEach(crypto::deleteBatch)
    }

    /**
     * Ends a batch whose retention ran out: withdraws what of it still counts, with the policy as
     * source, then deletes its data key. Both in the caller's transaction, so the logical and the
     * cryptographic state cannot drift apart (ADR-52).
     */
    fun expire(batch: ClaimBatchKey, at: Instant) {
        val accountId = checkNotNull(batch.accountId)
        val batchId = checkNotNull(batch.claimBatchId)
        established(accountId)
            .filter { it.claimBatchId == batchId }
            .map { checkNotNull(it.attributeType) to it.valueDigest }
            .distinct()
            .forEach { (type, digest) -> retract(accountId, type, digest, RetractionSource.RETENTION_POLICY, "Aufbewahrungsfrist abgelaufen", at) }
        crypto.deleteBatch(batchId)
    }
}
