package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
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
class FreshProofStrategy : IntentStrategy<FreshProofState> {

    override val intent = AuthIntent.FRESH_PROOF
    override val stateRoots = listOf(FreshProofState::class)

    /** Never entered without a seed; only reachable as a sub-journey ([FreshProofState.forSubJourney]). */
    override fun initialState(ctx: JourneyContext): FreshProofState =
        FreshProofState.forSubJourney(ctx.acrFloor, FreshProofState.Purpose.GENERIC)

    override fun transition(state: FreshProofState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is FreshProofState.Start -> when (event) {
                // The step-up (the only sub-journey requested here) reached the level: that is the fresh proof. Short of it, nothing less may
                // stand in, or a session stuck below the level would pass via the very factor that
                // could not reach it.
                is JourneyEvent.SubJourneyFinished ->
                    if ((event.achievedAcr ?: AcrLevel.NONE) >= state.requiredAcr) Transition.Authenticated
                    else Transition.Cancel
                else -> gate(state, ctx) ?: freshness(state, ctx)
            }

            is FreshProofState.Reproving -> when (event) {
                is JourneyEvent.OfferExhausted -> Transition.Cancel
                // Any active factor suffices; it was booked as Action.Reconfirm, so nothing of it is recorded.
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> error("Reproving does not understand $event")
            }
        }

    /** Null once the session already carries [FreshProofState.requiredAcr] and the caller may proceed. */
    private fun gate(state: FreshProofState.Start, ctx: JourneyContext): Transition? {
        if (ctx hasReached state.requiredAcr) return null
        return Transition.RequireSubJourney(
            AuthIntent.STEP_UP,
            seedWith = StepUpState.forSubJourney(
                (ctx.currentAcr upTo state.requiredAcr).copy(allowReIdentification = state.purpose.allowReIdentification, reason = state.purpose.stepUpReason)
            ),
            resumeWith = state,
        )
    }

    /** Done on a recent proof, else one active factor is re-proven. */
    private fun freshness(state: FreshProofState.Start, ctx: JourneyContext): Transition {
        if (ctx.policy.hasFreshProof(ctx.evidence)) return Transition.Authenticated
        val candidates = CandidateTools.forReconfirmation(ctx.requireAccount(), ctx)
        // Unreachable on an authenticated channel; abort rather than pass silently.
        return if (candidates.isEmpty()) Transition.Abort(Text("Kein aktiver Faktor zur erneuten Bestaetigung verfuegbar"))
        else Transition.To(FreshProofState.Reproving(state.requiredAcr, state.purpose, Offer(candidates)))
    }
}
