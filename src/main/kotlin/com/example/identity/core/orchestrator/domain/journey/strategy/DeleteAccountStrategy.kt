package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.declineTool
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.DeleteAccountState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolOutcome

/**
 * Delete the account of an already authenticated channel (docs/journeys/delete-account.md). The
 * yes/no question comes first; only after "yes" does the gate at [Action.DeleteAccount.requiredAcr]
 * apply. A step-up counts as the fresh proof; otherwise one active factor is re-proven. That
 * re-proof authorizes only this one action, so it is not recorded as `MethodEvidence`.
 */
class DeleteAccountStrategy : IntentStrategy<DeleteAccountState> {

    override val intent = AuthIntent.DELETE_ACCOUNT

    override fun initialState(ctx: JourneyContext): DeleteAccountState = DeleteAccountState.ConfirmPending

    override fun transition(state: DeleteAccountState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is DeleteAccountState.ConfirmPending -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    // The gate applies only after "yes".
                    "accept" -> gate(ctx) ?: offerReconfirmation(ctx)
                    "decline" -> Transition.Cancel
                    else -> error("ConfirmPending does not understand answer '${event.answer}'")
                }
                // Resumed after the gate's step-up. Falling short cancels: offerReconfirmation()
                // accepts any factor at any level and would bypass the gate.
                is JourneyEvent.SubJourneyFinished -> {
                    val account = ctx.requireAccount()
                    if (event.intent == AuthIntent.STEP_UP && AcrLevel.rank(event.achievedAcr) >= AcrLevel.rank(Action.DeleteAccount.requiredAcr(account))) {
                        Transition.Perform(Action.DeleteAccount, resumeState = state)
                    } else {
                        Transition.Cancel
                    }
                }
                // The gate's step-up was declined.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                // The delete after the step-up ran; end the channel.
                is JourneyEvent.ActionCompleted -> Transition.Logout
                // Started: always present the prompt, unconditionally.
                else -> Transition.To(state)
            }

            is DeleteAccountState.ConfirmationRequired -> when (event) {
                is JourneyEvent.Abandoned -> {
                    declineTool(state, event.tool.toolId, ctx) { Transition.Cancel }
                }
                // Any active factor suffices; straight to the delete, without Action.AcceptProof.
                is JourneyEvent.Completed -> when (event.outcome) {
                    is ToolOutcome.Completed.Authenticated ->
                        Transition.Perform(Action.DeleteAccount, resumeState = state)
                    is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
                        error("${event.tool.toolId} is not offered by DELETE_ACCOUNT")
                }
                // The delete just ran - end the channel.
                is JourneyEvent.ActionCompleted -> Transition.Logout
                else -> error("ConfirmationRequired does not understand $event")
            }
        }

    /** Null once the session already carries loa2 and the caller may proceed. */
    private fun gate(ctx: JourneyContext): Transition? {
        val account = ctx.requireAccount()
        val requiredAcr = Action.DeleteAccount.requiredAcr(account)
        if (ctx.policy.isSatisfied(ctx.evidence, requiredAcr, account)) return null
        return Transition.RequireSubJourney(
            AuthIntent.STEP_UP,
            seedWith = StepUpState.forSubJourney(requiredAcr, ctx.currentAcr),
            resumeWith = DeleteAccountState.ConfirmPending
        )
    }

    private fun offerReconfirmation(ctx: JourneyContext): Transition {
        val candidates = CandidateTools.forReconfirmation(ctx.requireAccount(), ctx)
        // Unreachable on an authenticated channel; abort rather than delete silently.
        return if (candidates.isEmpty()) {
            Transition.Abort(Text("Kein aktiver Faktor zur erneuten Bestaetigung verfuegbar"))
        } else {
            Transition.To(DeleteAccountState.ConfirmationRequired(Offer(candidates)))
        }
    }
}
