package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.declineTool
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.LookupLoginState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.toAuthAbortMessage
import com.example.identity.contract.tool_api.ToolOutcome

/**
 * Log into an existing account without a paired device (docs/journeys/lookup-login.md): the user
 * names an identifier and proves a credential. There is no identification state, and the device
 * link arises only from [LookupLoginState.OfferBinding], after the user agrees.
 */
class LookupLoginStrategy : IntentStrategy<LookupLoginState> {

    override val intent = AuthIntent.LOOKUP_LOGIN

    override fun initialState(ctx: JourneyContext): LookupLoginState = LookupLoginState.Start

    override fun transition(state: LookupLoginState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is LookupLoginState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> settleOrRaise(ctx)
                // No new evidence - re-deriving would just re-request the same RE_IDENTIFY again.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> {
                    // Every tool that resolves the account itself; authCandidates would need an
                    // account, which does not exist yet.
                    val tools = CandidateTools.forLookupLogin(ctx)
                    if (tools.isEmpty()) Transition.Abort(Text("Es gibt kein Anmeldeverfahren, das nicht an ein Gerät gebunden ist"))
                    else Transition.To(LookupLoginState.Credential(Offer(tools)))
                }
            }

            // The first proof has no account yet, so a lookup tool resolves it; the executor
            // decides that from the tool's role (accountOfProof), not this state.
            is LookupLoginState.Credential -> when (event) {
                is JourneyEvent.Completed -> completed(event, state)
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) { Transition.Cancel }
                else -> settleOrRaise(ctx)
            }

            // A further factor runs against the account bound by the first one.
            is LookupLoginState.AdditionalFactor -> when (event) {
                is JourneyEvent.Completed -> completed(event, state)
                // Giving up cannot finish anyway: the floor is still unmet.
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) { Transition.Cancel }
                else -> settleOrRaise(ctx)
            }

            // The journey ends either way; only ANSWER_ACCEPT links the device.
            is LookupLoginState.OfferBinding -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ANSWER_ACCEPT -> Transition.Perform(Action.LinkDevice, resumeState = state)
                    ANSWER_DECLINE -> Transition.Authenticated
                    else -> error("OfferBinding does not understand answer '${event.answer}'")
                }
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> error("OfferBinding only accepts JourneyEvent.Answered")
            }

            is LookupLoginState.ConfirmDeviceRebind -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ANSWER_ACCEPT -> Transition.Perform(Action.LinkDevice, resumeState = state)
                    ANSWER_DECLINE -> Transition.Authenticated
                    else -> error("ConfirmDeviceRebind does not understand answer '${event.answer}'")
                }
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> error("ConfirmDeviceRebind only accepts JourneyEvent.Answered")
            }
        }

    /**
     * A one-time password opens a process access on the website only (ADR-48): the App channel's
     * journeys and tokens know no subject other than an account. The tool is switched off for the
     * App by default; should an App announce it anyway, the journey ends with a clear refusal
     * instead of binding an invitation it cannot serve.
     */
    private fun completed(event: JourneyEvent.Completed, state: LookupLoginState): Transition {
        val outcome = event.outcome
        if (outcome is ToolOutcome.Completed.Authenticated && outcome.subject is Subject.Invitation) {
            return Transition.Abort(Text("Ein Einmalkennwort gilt nur auf der Website"))
        }
        return Transition.Perform(proofAction(event), resumeState = state)
    }

    /** Only authentication tools are offered; any other outcome means a tool ran that was never offered. */
    private fun proofAction(event: JourneyEvent.Completed): Action =
        when (val outcome = event.outcome) {
            is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome)
            is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
                error("${event.tool.toolId} is not offered by LOGIN_LOOKUP")
        }

    /**
     * Checks the channel's acrFloor, since finishing sets AUTHENTICATED unconditionally. There is no
     * enrollment fallback: an account that cannot reach the floor must not grow new credentials on
     * an unproven device. Re-identification stays available; it adds no lasting credential.
     */
    private fun settleOrRaise(ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            // Unlinked: offer the link. Linked elsewhere: warn before rebinding. Linked here:
            // nothing to ask.
            return when (ctx.linkedAccountId) {
                null -> Transition.To(LookupLoginState.OfferBinding)
                account.accountId -> Transition.Authenticated
                else -> Transition.To(LookupLoginState.ConfirmDeviceRebind)
            }
        }
        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) {
            return Transition.To(LookupLoginState.AdditionalFactor(Offer(candidates)))
        }
        return if (CandidateTools.forReIdentification(ctx.acrFloor, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(
                AuthIntent.RE_IDENTIFY,
                seedWith = ReIdentifyState.forSubJourney(ctx.acrFloor, ctx.currentAcr),
                resumeWith = LookupLoginState.Start
            )
        } else {
            Transition.Abort(ctx.policy.reachability(account, ctx.acrFloor).toAuthAbortMessage())
        }
    }

    companion object {
    }
}
