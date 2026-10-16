package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.JourneyStart
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.StartableWhenAuthenticated
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.toAuthAbortMessage
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState.Goal
import com.example.identity.core.orchestrator.domain.policy.EvidenceAxis

/**
 * Raise the level of an already authenticated session (docs/journeys/step-up.md). When no active
 * method can close the gap, the `RE_IDENTIFY` sub-journey asks before any fresh identification.
 */
object StepUpJourney : Journey<StepUpState> {

    override val name = "STEP_UP"
    override val startPoints = listOf(StartableWhenAuthenticated)
    override val stateRoots = listOf(StepUpState::class)

    /** A step-up toward [goal]: never started without one. */
    fun toward(goal: Goal): JourneyStart<*> = JourneyStart(this, StepUpState.Start(goal))

    override fun transition(state: StepUpState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is StepUpState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> finishOrContinue(state.goal, ctx)
                else -> offerAuth(state.goal, ctx)
            }

            is StepUpState.AuthChoice -> when (event) {
                is JourneyEvent.OfferExhausted -> offerReIdentOrGiveUp(state.goal, ctx, whenNone = Transition.Cancel)
                // ActionCompleted: re-check with the fresh, post-proof context.
                else -> finishOrContinue(state.goal, ctx)
            }
        }

    private fun finishOrContinue(goal: Goal, ctx: JourneyContext): Transition =
        if (ctx hasReached goal.targetAcr) Transition.Authenticated
        else offerAuth(goal, ctx)

    private fun offerAuth(goal: Goal, ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        val candidates = CandidateTools.forAuth(account, goal.targetAcr, ctx)
        if (candidates.isNotEmpty()) {
            // Authenticator evidence already on the channel means this offer asks for an
            // additional factor (StepUpState.AuthChoice.additionalFactorRound).
            val additionalFactorRound = ctx.evidence.methods.any { it.axis == EvidenceAxis.AUTHENTICATOR }
            return Transition.To(
                StepUpState.AuthChoice(goal, Offer(candidates), additionalFactorRound = additionalFactorRound)
            )
        }
        return offerReIdentOrGiveUp(
            goal, ctx,
            whenNone = Transition.Abort(ctx.policy.reachability(account, goal.targetAcr).toAuthAbortMessage())
        )
    }

    /**
     * Offers re-identification if allowed ([Goal.allowReIdentification]) and able to
     * close the gap; [whenNone] otherwise.
     */
    private fun offerReIdentOrGiveUp(goal: Goal, ctx: JourneyContext, whenNone: Transition): Transition {
        if (!goal.allowReIdentification) return whenNone
        // The whole goal, or the run would forget on return why and how it may step up.
        return reIdentifyOr(goal.targetAcr, ctx, resumeWith = StepUpState.Start(goal)) { whenNone }
    }
}
