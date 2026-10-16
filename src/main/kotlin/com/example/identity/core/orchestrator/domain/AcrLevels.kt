package com.example.identity.core.orchestrator.domain

import com.example.identity.contract.tool_api.claims.AcrLevel

/**
 * The ACR decisions only the orchestrator makes: the session floor, the "no ceiling" value and the
 * MFA bump (docs/04-orchestrierung.md #4). Level names and their ordering are [AcrLevel] in
 * tool_api. What earns a level is decided by the policy, not here.
 */
object AcrLevels {
    /** Baseline floor when neither the channel nor a step-up process names one explicitly. */
    val DEFAULT_REQUIRED_ACR = AcrLevel.LOA1

    /** Moves [acr] up by [steps] tiers, capped at the highest known level (MFA bump). */
    fun bump(acr: AcrLevel, steps: Int = 1): AcrLevel =
        AcrLevel.levelAt((AcrLevel.rank(acr) + steps).coerceAtMost(AcrLevel.KNOWN.size - 1))
}
