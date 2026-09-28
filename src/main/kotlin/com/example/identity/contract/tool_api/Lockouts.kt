package com.example.identity.contract.tool_api

/**
 * The orchestrator's locks after failed guesses, asked by tools that resolve their subject
 * themselves. The caller must treat `true` like an unknown address or number, never as its own
 * error: an observable lockout would tell an attacker which accounts or persons exist. A module's
 * send budget ([com.example.identity.contract.tool_api.budget.AttemptBudget]) is a different thing:
 * a rate on sending, not a lock on guessing (docs/07-betrieb.md #4).
 */
interface Lockouts {
    /**
     * Whether [accountId] is locked after failed AUTH attempts. For LOOKUP_AUTH tools; for
     * IDENTIFIED_AUTH [ToolJourney.beginActivation] checks it. `null` answers `false`.
     */
    fun isLockedOut(accountId: Long?): Boolean

    /** Whether [personId] is locked after failed IDENT attempts. `null` answers `false`. */
    fun isIdentLockedOut(personId: String?): Boolean
}
