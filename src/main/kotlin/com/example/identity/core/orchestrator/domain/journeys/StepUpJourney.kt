package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.authCandidates
import com.example.identity.core.orchestrator.domain.journey.JourneyStart
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.StartableWhenAuthenticated
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.notExpectedIn
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
    override val stateRoot = StepUpState::class

    /** A step-up toward [goal]: never started without one. */
    fun toward(goal: Goal): JourneyStart<*> = JourneyStart(this, StepUpState.Start(goal))

    override fun transition(state: StepUpState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is StepUpState.Start -> when (event) {
                // Fresh, or back from RE_IDENTIFY: the session may already have reached the goal.
                JourneyEvent.Started, is JourneyEvent.SubJourneyFinished -> finishOrContinue(state.goal, ctx)
                else -> event.notExpectedIn(state)
            }

            is StepUpState.AuthChoice -> when (event) {
                JourneyEvent.ActionCompleted -> finishOrContinue(state.goal, ctx)
                is JourneyEvent.OfferExhausted -> offerReIdentOrGiveUp(state.goal, ctx, whenNone = Transition.Cancel)
                else -> event.notExpectedIn(state)
            }
        }

    private fun finishOrContinue(goal: Goal, ctx: JourneyContext): Transition =
        if (ctx hasReached goal.targetAcr) Transition.Authenticated
        else offerAuth(goal, ctx)

    private fun offerAuth(goal: Goal, ctx: JourneyContext): Transition {
        // Authenticator evidence already on the channel means this offer asks for an additional
        // factor (StepUpState.AuthChoice.additionalFactorRound).
        val additionalFactorRound = ctx.evidence.methods.any { it.axis == EvidenceAxis.AUTHENTICATOR }
        val candidates = ctx.authCandidates(goal.targetAcr)
        if (candidates.isNotEmpty()) {
            return Transition.To(StepUpState.AuthChoice(goal, Offer(candidates), additionalFactorRound = additionalFactorRound))
        }
        return offerReIdentOrGiveUp(goal, ctx, whenNone = cannotReach(goal, ctx))
    }

    /** Nothing gets the account to [Goal.targetAcr]: say why. */
    private fun cannotReach(goal: Goal, ctx: JourneyContext): Transition =
        Transition.Abort(ctx.policy.reachability(ctx.requireAccount(), goal.targetAcr).toAuthAbortMessage())

    /**
     * Offers re-identification if allowed ([Goal.allowsReIdentification]) and able to
     * close the gap; [whenNone] otherwise.
     */
    private fun offerReIdentOrGiveUp(goal: Goal, ctx: JourneyContext, whenNone: Transition): Transition {
        if (!goal.allowsReIdentification) return whenNone
        // The whole goal, or the run would forget on return why and how it may step up.
        return ReIdentifyJourney.toReachOr(goal.targetAcr, ctx, resumeWith = StepUpState.Start(goal)) { whenNone }
    }
}
