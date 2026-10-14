package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.DeleteAccountState

/**
 * Delete the account of an already authenticated channel (docs/journeys/delete-account.md). The
 * yes/no question comes first; only after "yes" does the gate at [Action.DeleteAccount.requiredAcr]
 * apply, together with the fresh proof, both as the `FRESH_PROOF` sub-journey (ADR-61).
 */
class DeleteAccountStrategy : IntentStrategy<DeleteAccountState> {

    override val intent = AuthIntent.DELETE_ACCOUNT
    override val stateRoots = listOf(DeleteAccountState::class)

    override fun initialState(ctx: JourneyContext): DeleteAccountState = DeleteAccountState.ConfirmPending

    override fun transition(state: DeleteAccountState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is DeleteAccountState.ConfirmPending -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    // Level and freshness are checked only after "yes".
                    ANSWER_ACCEPT -> requireFreshProof(ctx)
                    ANSWER_DECLINE -> Transition.Cancel
                    else -> event.notUnderstood("ConfirmPending")
                }
                is JourneyEvent.SubJourneyFinished -> Transition.Perform(Action.DeleteAccount, resumeState = state)
                // The delete ran; end the channel.
                is JourneyEvent.ActionCompleted -> Transition.Logout
                // Started: always present the prompt, unconditionally.
                else -> Transition.To(state)
            }
        }

    private fun requireFreshProof(ctx: JourneyContext): Transition = Transition.RequireSubJourney(
        AuthIntent.FRESH_PROOF,
        seedWith = FreshProofState.forSubJourney(Action.DeleteAccount.requiredAcr(ctx.requireAccount()), FreshProofState.Purpose.DELETE_ACCOUNT),
        resumeWith = DeleteAccountState.ConfirmPending,
    )
}
