package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.offer
import com.example.identity.core.orchestrator.domain.journey.reconfirmationCandidates
import com.example.identity.core.orchestrator.domain.journey.state.ProofPurpose
import com.example.identity.core.orchestrator.domain.journey.JourneyStart
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.OnlyAsSubJourney
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.notExpectedIn
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.upTo
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState

/**
 * The one place that decides "level reached, proof recent" before a self-service action
 * (docs/journeys/fresh-proof.md, ADR-61). A step-up this sub-journey required counts as the fresh
 * proof; any other recent proof of the session does too; otherwise one active factor is re-proven.
 * That re-proof authorizes the caller's one action only, so it is not recorded as `MethodEvidence`.
 * Freshness is checked once, when this sub-journey starts; a caller that offers a tool afterwards
 * gives the user until the journey expires to finish it (docs/journeys/fresh-proof.md).
 */
object FreshProofJourney : Journey<FreshProofState> {

    override val name = "FRESH_PROOF"
    override val startPoints = listOf(OnlyAsSubJourney)
    override val stateRoot = FreshProofState::class

    /** The gate before an action for [purpose]: [requiredAcr] reached and a recent proof. */
    fun before(purpose: ProofPurpose, requiredAcr: AcrLevel): JourneyStart<*> =
        JourneyStart(this, FreshProofState.Start(requiredAcr, purpose))

    override fun transition(state: FreshProofState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is FreshProofState.Start -> when (event) {
                // The step-up (the only sub-journey requested here) reached the level: that is the fresh proof. Short of it, nothing less may
                // stand in, or a session stuck below the level would pass via the very factor that
                // could not reach it.
                is JourneyEvent.SubJourneyFinished ->
                    if (event.achievedAcr >= state.requiredAcr) Transition.Authenticated
                    else Transition.Cancel
                JourneyEvent.Started ->
                    if (ctx hasReached state.requiredAcr) freshness(state, ctx)
                    else stepUpFirst(state, ctx)
                else -> event.notExpectedIn(state)
            }

            is FreshProofState.Reproving -> when (event) {
                is JourneyEvent.OfferExhausted -> Transition.Cancel
                // Any active factor suffices; it was booked as Action.Reconfirm, so nothing of it is recorded.
                JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> event.notExpectedIn(state)
            }
        }

    /** The session is below [FreshProofState.requiredAcr]: a step-up first, which then counts as the fresh proof. */
    private fun stepUpFirst(state: FreshProofState.Start, ctx: JourneyContext): Transition =
        Transition.RequireSubJourney(
            StepUpJourney.toward(
                (ctx.currentAcr upTo state.requiredAcr).copy(purpose = state.purpose)
            ),
            resumeWith = state,
        )

    /** Done on a recent proof, else one active factor is re-proven. */
    private fun freshness(state: FreshProofState.Start, ctx: JourneyContext): Transition {
        if (ctx.policy.hasFreshProof(ctx.evidence)) return Transition.Authenticated
        return offer(ctx.reconfirmationCandidates()) { FreshProofState.Reproving(state.requiredAcr, state.purpose, it) }
            // Unreachable on an authenticated channel; abort rather than pass silently.
            ?: Transition.Abort(Text("Kein aktiver Faktor zur erneuten Bestaetigung verfuegbar"))
    }
}
