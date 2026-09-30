package com.example.identity.core.account.domain

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AnchorRule
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType

/**
 * What writing an anchor value means for one account. Every lookup-login resolves through the
 * anchor, so this is where a takeover would happen. Pure: `AnchorRegistry` reads, asks here, writes (ADR-40).
 */
sealed interface AnchorDecision {
    /** This account already holds exactly this value - nothing to write. */
    data object AlreadyHeld : AnchorDecision

    /** First binding of a value for this type on this account. */
    data object Establish : AnchorDecision

    /**
     * This account's own anchor gets a new value in place. [retractFromLog] is the replaced value, to
     * be withdrawn so the log agrees with the anchor (ADR-19). It is `null` when old and new are the
     * same value in the log (a pseudonym differing only in case), or the retraction would void the
     * claim just being set.
     */
    data class Replace(val retractFromLog: String?) : AnchorDecision

    companion object {
        /**
         * A value held by another account is refused, never re-assigned (ADR-11). A new value for
         * this account's own anchor needs a [rule] that allows replacement. Both writes are refused
         * below [AnchorRule.acrFloor] rather than logged without the anchor: a caller that believed
         * it bound an identity must not proceed on a false premise.
         *
         * @param heldBy the account that holds [value] as its [type] anchor today, if any.
         * @param currentValue this account's current [type] anchor value, if any.
         */
        fun decide(
            accountId: AccountId,
            type: AttributeType,
            value: String,
            heldBy: AccountId?,
            currentValue: String?,
            rule: AnchorRule,
            provenAcr: AcrLevel,
        ): AnchorDecision {
            if (heldBy != null) {
                if (heldBy == accountId) return AlreadyHeld
                throw IdentityConflictException(
                    Text("Dieser {type}-Wert gehoert bereits zu einem anderen Konto", "type" to type.wireName),
                    "${type.wireName} anchor already held by account $heldBy, rejected for account $accountId",
                )
            }
            if (currentValue == null) {
                requirePaid(type, provenAcr, rule.acrFloor.establish, replacing = false)
                return Establish
            }
            if (!rule.allowsReplacement) {
                throw IdentityConflictException(
                    Text("Dieser {type}-Wert kann fuer dieses Konto nicht mehr geaendert werden", "type" to type.wireName),
                    "${type.wireName} for account $accountId is immutable",
                )
            }
            requirePaid(type, provenAcr, rule.acrFloor.replace, replacing = true)
            val replaced = normalizeClaimValue(type, currentValue)
            return Replace(retractFromLog = replaced.takeIf { it != normalizeClaimValue(type, value) })
        }

        private fun requirePaid(type: AttributeType, provenAcr: AcrLevel, floor: AcrLevel, replacing: Boolean) {
            if (AcrLevel.rank(provenAcr) >= AcrLevel.rank(floor)) return
            throw IdentityConflictException(
                // Two sentences, not one with the verb as a value: every language inflects a verb itself.
                if (replacing) {
                    Text("Dieser {type}-Wert kann erst ab {floor} ersetzt werden, nachgewiesen ist {provenAcr}", "type" to type.wireName, "floor" to floor.value, "provenAcr" to provenAcr.value)
                } else {
                    Text("Dieser {type}-Wert kann erst ab {floor} gesetzt werden, nachgewiesen ist {provenAcr}", "type" to type.wireName, "floor" to floor.value, "provenAcr" to provenAcr.value)
                },
                "${type.wireName} ${if (replacing) "replace" else "establish"} needs ${floor.value}, session proved ${provenAcr.value}",
            )
        }
    }
}
