package com.example.identity.core.account.application

import com.example.identity.core.account.infrastructure.strongestEstablishedValues
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.AccountClaimRepository
import com.example.identity.core.account.infrastructure.AccountRetraction
import com.example.identity.core.account.infrastructure.AccountRetractionRepository
import com.example.identity.core.account.domain.ClaimKey
import com.example.identity.core.account.domain.normalizeClaimValue
import com.example.identity.core.account.RetractionAnchor
import com.example.identity.contract.tool_api.claims.AttributeAuthority
import com.example.identity.contract.tool_api.claims.authority
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.TrustLevel
import com.example.identity.contract.tool_api.claims.trustLevel
import com.example.identity.contract.tool_api.claims.validateValue
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The account's claim log (docs/02-domaenenmodell.md #6): what was asserted, by which source and
 * method, and what was withdrawn (ADR-12). Only appends and reads; `AccountService` decides locking
 * and order. Every withdrawal goes through [retract], so none escapes the change log (ADR-39).
 */
@Component
class ClaimLedger(
    private val accountClaimRepository: AccountClaimRepository,
    private val accountRetractionRepository: AccountRetractionRepository,
    private val changeLog: ChangeLog,
    private val clock: Clock,
) {

    /**
     * Appends the [claims] of one completed tool run, at most one per [AttributeType]; an already
     * logged [ClaimKey] adds no row. [provenAcr] caps what a claim establishes (ADR-5).
     *
     * @return each claim with its establishing instant, which the following anchor write reuses.
     */
    fun append(accountId: Long, claims: List<Claim>, provenAcr: AcrLevel, authMethodId: UUID?): List<Pair<Claim, Instant>> {
        val seen = mutableSetOf<AttributeType>()
        claims.forEach { claim ->
            claim.validateValue()
            check(seen.add(claim.attributeType)) {
                "recordClaims($accountId): more than one claim for ${claim.attributeType.wireName}"
            }
        }
        val logged = accountClaimRepository.findEstablished(accountId)
            .map { claim ->
                ClaimKey(
                    checkNotNull(claim.attributeType),
                    checkNotNull(claim.normalizedValue),
                    checkNotNull(claim.claimSource),
                    claim.authMethodId
                )
            }
            .toMutableSet()
        return claims.map { claim ->
            val establishedAt = clock.instant()
            if (logged.add(
                    ClaimKey(
                        claim.attributeType,
                        checkNotNull(normalizeClaimValue(claim.attributeType, claim.value)),
                        claim.source.value,
                        authMethodId
                    )
                )
            ) {
                accountClaimRepository.save(
                    AccountClaim(
                        accountId = accountId,
                        attributeType = claim.attributeType,
                        value = claim.value,
                        claimSource = claim.source.value,
                        // What was actually proven, not what the tool can reach at most (ADR-5).
                        establishedAcr = (claim.establishedAcr?.let { AcrLevel.min(it, provenAcr) } ?: provenAcr).value,
                        authMethodId = authMethodId,
                        establishedAt = establishedAt
                    )
                )
            }
            claim to establishedAt
        }
    }

    /** Assertions minus retractions - the one view every reader uses. */
    fun established(accountId: Long): List<AccountClaim> = accountClaimRepository.findEstablished(accountId)

    /** The established VALUES for [types], strongest assertion per attribute. */
    fun establishedValues(accountId: Long, types: Set<AttributeType>): Map<AttributeType, String> =
        established(accountId).strongestEstablishedValues(types)

    /** The same, counting only sources that PROVE the value - self-reported ones never do. */
    fun provenValues(accountId: Long, types: Set<AttributeType>): Map<AttributeType, String> =
        established(accountId)
            .filter { ClaimSource(it.claimSource.orEmpty()).trustLevel.rank >= TrustLevel.PROVEN.rank }
            .strongestEstablishedValues(types)

    /** Highest [TrustLevel] per established attribute. */
    fun establishedTrust(accountId: Long): Map<AttributeType, TrustLevel> =
        established(accountId)
            .mapNotNull { claim ->
                val type = claim.attributeType ?: return@mapNotNull null
                val source = claim.claimSource?.let(::ClaimSource) ?: return@mapNotNull null
                type to source.trustLevel
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, levels) -> levels.maxBy { it.rank } }

    /** What method instance [instanceId] asserted that belongs to its method module, as (type, value). */
    fun ownedBy(accountId: Long, instanceId: UUID): Set<Pair<AttributeType, String?>> =
        accountClaimRepository.findByAuthMethodId(instanceId)
            .filter { it.accountId == accountId && it.attributeType?.authority == AttributeAuthority.MethodModule }
            .mapNotNull { claim -> claim.attributeType?.let { it to claim.normalizedValue } }
            .toSet()

    /**
     * Withdraws every established value of [attributeType] - one retraction row per distinct value.
     * @return true if something was established and is now withdrawn.
     */
    fun retractEstablished(accountId: Long, attributeType: AttributeType, trustAnchor: RetractionAnchor, reason: String?, at: Instant): Boolean {
        val established = established(accountId)
            .filter { it.attributeType == attributeType }
            .map { it.normalizedValue }
            .distinct()
        established.forEach { retract(accountId, attributeType, it, trustAnchor, reason, at) }
        return established.isNotEmpty()
    }

    /** One withdrawal, logged in the change log; the value stays in the retraction row, which goes with the account. */
    fun retract(accountId: Long, type: AttributeType, normalizedValue: String?, trustAnchor: RetractionAnchor, reason: String?, at: Instant) {
        changeLog.attributeRetracted(accountId, type.name, trustAnchor = trustAnchor.name, reason = reason, at = at)
        accountRetractionRepository.save(
            AccountRetraction(
                accountId = accountId,
                attributeType = type,
                normalizedValue = normalizedValue,
                trustAnchor = trustAnchor,
                reason = reason,
                retractedAt = at
            )
        )
    }
}
