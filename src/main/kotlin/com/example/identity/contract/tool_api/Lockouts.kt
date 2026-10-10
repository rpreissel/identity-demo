package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber

/**
 * The orchestrator's locks after failed guesses, asked by tools that resolve their subject
 * themselves. The caller must treat `true` like an unknown address or number, never as its own
 * error: an observable lockout would tell an attacker which accounts or persons exist. A module's
 * send budget ([com.example.identity.contract.tool_api.ratelimit.RateLimit]) is a different thing:
 * a rate on sending, not a lock on guessing (docs/07-betrieb.md #4).
 */
interface Lockouts {
    /**
     * Whether [accountId] is locked after failed AUTH attempts. For ACCOUNT_LOOKUP_AUTH tools; for
     * KNOWN_ACCOUNT_AUTH [ToolJourney.beginActivation] checks it. `null` answers `false`.
     */
    fun isLockedOut(accountId: AccountId?): Boolean

    /**
     * Books one attempt on [accountId] right before a tool checks a secret against it; `false` if
     * the account is locked, and then nothing is booked. The check and the count are one step, so
     * parallel attempts cannot all pass before the fifth counts (docs/07-betrieb.md #4). A tool
     * that books names the account in its outcome; the orchestrator does not count it again.
     */
    fun admitAttempt(accountId: AccountId): Boolean

    /** Whether [personId] is locked after failed IDENT attempts. `null` answers `false`. */
    fun isIdentLockedOut(personId: PartnerNumber?): Boolean
}
