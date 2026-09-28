package com.example.identity.core.orchestrator.domain

import com.example.identity.contract.tool_api.claims.AcrLevel

/**
 * The ACR decisions only the orchestrator makes: the session floor, the "no ceiling" value and the
 * MFA bump (docs/04-orchestrierung.md #8). Level names and their ordering are [AcrLevel] in
 * tool_api. What earns a level is decided by the policy, not here.
 */
object AcrLevels {
    /** Baseline floor when neither the channel nor a step-up process names one explicitly. */
    val DEFAULT_REQUIRED_ACR = AcrLevel.LOA1

    /** The highest known level: the "no ceiling" value for a caller that does not cap an MFA bump. */
    val HIGHEST: AcrLevel get() = AcrLevel(AcrLevel.KNOWN.last())

    /** Moves [acr] up by [steps] tiers, capped at the highest known level (MFA bump). */
    fun bump(acr: AcrLevel, steps: Int = 1): AcrLevel =
        AcrLevel.levelAt((AcrLevel.rank(acr) + steps).coerceAtMost(AcrLevel.KNOWN.size - 1))

    // String-typed edge mirrors -------------------------------------------------------------------
    // The typed taxonomy for borders that carry plain strings (JPA columns, wire DTOs, tokens).
    // Unknown values count as "none": results are only rank-compared or written into validated
    // fields.

    /** [AcrLevel.rank], for a raw string - null or unknown counts as rank 0. */
    fun rank(acr: String?): Int = AcrLevel.rank(AcrLevel.of(acr))

    /** [AcrLevel.levelAt], as the raw level name - "none" if out of range. */
    fun levelAt(rank: Int): String = AcrLevel.levelAt(rank).value

    /** [AcrLevel.max], for raw strings - a null side falls back to the other, both null -> "none". */
    fun max(a: String?, b: String?): String = AcrLevel.max(AcrLevel.of(a), AcrLevel.of(b)).value

    /** [AcrLevel.min], for raw strings - any null side means nothing was established -> "none". */
    fun min(a: String?, b: String?): String = AcrLevel.min(AcrLevel.of(a), AcrLevel.of(b)).value
}
