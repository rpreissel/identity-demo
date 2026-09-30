package com.example.identity.core.account.application

import com.example.identity.core.account.infrastructure.AccountAnchor
import com.example.identity.core.account.infrastructure.AccountAnchorRepository
import com.example.identity.core.account.domain.AnchorDecision
import com.example.identity.core.account.RetractionSource
import com.example.identity.contract.tool_api.claims.anchorRule
import com.example.identity.contract.tool_api.claims.normalizeAnchorValue
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * The account's anchors: the locally owned identifiers an account is found by, one row per account
 * and type, one account per value. `AccountService` takes the account lock before calling [bind].
 */
@Component
class AnchorRegistry(
    private val accountAnchorRepository: AccountAnchorRepository,
    private val claimLedger: ClaimLedger,
) {
    /**
     * Materializes an anchor row as [AnchorDecision] decides (ADR-11, ADR-19, ADR-5 floors). The
     * caller holds the account lock (`AccountService.recordClaims`).
     */
    fun bind(accountId: Long, type: AttributeType, value: String, establishedAt: Instant, provenAcr: AcrLevel) {
        val rule = checkNotNull(type.anchorRule) { "$type is not a local anchor attribute" }
        val normalized = type.normalizeAnchorValue(value)
        val existing = accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)
        val decision = AnchorDecision.decide(
            accountId, type, value,
            heldBy = accountAnchorRepository.findByAttributeTypeAndValue(type, normalized)?.accountId,
            currentValue = existing?.value,
            rule = rule,
            provenAcr = provenAcr,
        )
        when (decision) {
            AnchorDecision.AlreadyHeld -> Unit
            AnchorDecision.Establish -> accountAnchorRepository.save(
                AccountAnchor(accountId = accountId, attributeType = type, value = normalized, establishedAcr = provenAcr.value, establishedAt = establishedAt)
            )
            // Updated in place rather than deleted and re-inserted: Hibernate flushes insertions
            // before deletions, so the pair would briefly hold two rows and trip ux_anchor_account_type.
            is AnchorDecision.Replace -> {
                decision.retractFromLog?.let {
                    claimLedger.retract(accountId, type, it, RetractionSource.ACCOUNT_MANAGEMENT, "anker-ersetzt", establishedAt)
                }
                val row = checkNotNull(existing)
                row.value = normalized
                row.establishedAcr = provenAcr.value
                row.establishedAt = establishedAt
                accountAnchorRepository.save(row)
            }
        }
    }

    /** The account holding [value] as its [type] anchor, or `null`. */
    fun holderOf(type: AttributeType, value: String): Long? =
        accountAnchorRepository.findByAttributeTypeAndValue(type, type.normalizeAnchorValue(value))?.accountId

    /** This account's [type] anchor value, or `null`. */
    fun valueOf(accountId: Long, type: AttributeType): String? =
        accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)?.value

    fun anchorsOf(accountId: Long): List<AccountAnchor> = accountAnchorRepository.findByAccountId(accountId)

    /** Deletes this account's [type] anchor, if any (ADR-12: nothing resolves the account by it afterwards). */
    fun remove(accountId: Long, type: AttributeType) {
        accountAnchorRepository.findByAccountIdAndAttributeType(accountId, type)?.let { accountAnchorRepository.delete(it) }
    }

    /**
     * Releases [anchors] and flushes at once: Hibernate inserts before it deletes within one flush,
     * so writing the same values on another account would otherwise trip `ux_anchor_value`.
     */
    fun releaseNow(anchors: List<AccountAnchor>) {
        accountAnchorRepository.deleteAll(anchors)
        accountAnchorRepository.flush()
    }
}
