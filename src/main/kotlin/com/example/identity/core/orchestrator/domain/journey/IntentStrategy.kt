package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.AuthIntent

/**
 * The ACR floor for self-service actions on an account's own credentials and data: a hijacked loa1
 * session must not act as if it had proved more. Identified accounts need loa2. A never-identified
 * account ([AccountProfile.personId] `== null`, "Enrollment zuerst") needs loa1: it has no bound
 * identity to expose, and it could never reach loa2 without an identification anyway. That the
 * mailbox alone then suffices for destructive self-service is deliberate (ADR-37).
 */
fun selfServiceAcrFloor(account: AccountProfile?): AcrLevel =
    if (account?.personId == null) AcrLevels.DEFAULT_REQUIRED_ACR else AcrLevel.LOA2

/**
 * The SPI each intent implements (docs/04-orchestrierung.md #5). A strategy decides, it never acts:
 * it reads a [JourneyContext] and names an [Action]; every side effect is executed centrally by
 * `JourneyService`. [transition] is the one place a strategy answers "what happens next"; every
 * trigger is a [JourneyEvent].
 */
interface IntentStrategy<S : JourneyState> {
    /** Which [AuthIntent] this strategy implements, one bean per enum entry. */
    val intent: AuthIntent

    /** Where a fresh journey of this intent begins, before any event has been seen. */
    fun initialState(ctx: JourneyContext): S

    /**
     * The transition function. After a [Transition.Perform], JourneyService executes the [Action],
     * refreshes the [JourneyContext] and calls this again with [JourneyEvent.ActionCompleted], so
     * follow-up logic (e.g. "is the floor satisfied now?") sees the post-action context.
     */
    fun transition(state: S, event: JourneyEvent, ctx: JourneyContext): Transition
}
