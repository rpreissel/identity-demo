package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.authCandidates
import com.example.identity.core.orchestrator.domain.journey.JourneyStart
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.StartableWhenAuthenticated
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.AccountJourney
import com.example.identity.core.orchestrator.domain.journey.AccountJourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.notExpectedIn
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.offer
import com.example.identity.core.orchestrator.domain.journey.toAuthAbortMessage
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState.Goal

/**
 * Raise the level of an already authenticated session (docs/journeys/step-up.md). When no active
 * method can close the gap, the `RE_IDENTIFY` sub-journey asks before any fresh identification.
 */
object StepUpJourney : AccountJourney<StepUpState> {

    override val name = "STEP_UP"
    override val startPoints = listOf(StartableWhenAuthenticated)
    override val stateRoot = StepUpState::class

    /** A step-up toward [goal]: never started without one. */
    fun toward(goal: Goal): JourneyStart<*> = JourneyStart(this, StepUpState.Start(goal))

    override fun transition(state: StepUpState, event: JourneyEvent, ctx: AccountJourneyContext): Transition =
        when (state) {
            is StepUpState.Start -> when (event) {
                // Fresh, or back from RE_IDENTIFY: the session may already have reached the goal.
                JourneyEvent.Started, is JourneyEvent.SubJourneyFinished -> settle(state.goal, ctx)
                else -> event.notExpectedIn(state)
            }

            is StepUpState.AuthChoice -> when (event) {
                JourneyEvent.ActionCompleted -> settle(state.goal, ctx)
                is JourneyEvent.OfferExhausted -> offerReIdentOrGiveUp(state.goal, ctx, whenNone = Transition.Cancel)
                else -> event.notExpectedIn(state)
            }
        }

    /** Settles the run: done once [goal] is reached, else the next offer. */
    private fun settle(goal: Goal, ctx: AccountJourneyContext): Transition =
        if (ctx hasReached goal.targetAcr) Transition.Authenticated
        else offerAuth(goal, ctx)

    private fun offerAuth(goal: Goal, ctx: AccountJourneyContext): Transition =
        offer(ctx.authCandidates(goal.targetAcr)) { StepUpState.AuthChoice(goal, it) }
            ?: offerReIdentOrGiveUp(goal, ctx, whenNone = cannotReach(goal, ctx))

    /** Nothing gets the account to [Goal.targetAcr]: say why. */
    private fun cannotReach(goal: Goal, ctx: AccountJourneyContext): Transition =
        Transition.Abort(ctx.policy.reachability(ctx.account, goal.targetAcr).toAuthAbortMessage())

    /**
     * Offers re-identification if allowed ([Goal.allowsReIdentification]) and able to
     * close the gap; [whenNone] otherwise.
     */
    private fun offerReIdentOrGiveUp(goal: Goal, ctx: AccountJourneyContext, whenNone: Transition): Transition {
        if (!goal.allowsReIdentification) return whenNone
        // The whole goal, or the run would forget on return why and how it may step up.
        return ReIdentifyJourney.toReachOr(goal.targetAcr, ctx, resumeWith = StepUpState.Start(goal)) { whenNone }
    }
}
