package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.JourneyWithoutInput
import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.StartableWhenAuthenticated
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.Answer
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.DeleteAccountState

/**
 * Delete the account of an already authenticated channel (docs/journeys/delete-account.md). The
 * yes/no question comes first; only after "yes" does the gate at [Action.DeleteAccount.requiredAcr]
 * apply, together with the fresh proof, both as the `FRESH_PROOF` sub-journey (ADR-61).
 */
object DeleteAccountJourney : JourneyWithoutInput<DeleteAccountState> {

    override val name = "DELETE_ACCOUNT"
    override val startPoints = listOf(StartableWhenAuthenticated)
    override val stateRoots = listOf(DeleteAccountState::class)

    override val firstState = DeleteAccountState.ConfirmPending

    override fun transition(state: DeleteAccountState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is DeleteAccountState.ConfirmPending -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    // Level and freshness are checked only after "yes".
                    Answer.ACCEPT -> requireFreshProof(ctx)
                    Answer.DECLINE -> Transition.Cancel
                }
                is JourneyEvent.SubJourneyFinished -> Transition.Perform(Action.DeleteAccount, resumeState = state)
                // The delete ran; end the channel.
                is JourneyEvent.ActionCompleted -> Transition.Logout
                // Started: always present the prompt, unconditionally.
                else -> Transition.To(state)
            }
        }

    private fun requireFreshProof(ctx: JourneyContext): Transition = Transition.RequireSubJourney(
        FreshProofJourney.before(FreshProofState.Purpose.DELETE_ACCOUNT, Action.DeleteAccount.requiredAcr(ctx.requireAccount())),
        resumeWith = DeleteAccountState.ConfirmPending,
    )
}
