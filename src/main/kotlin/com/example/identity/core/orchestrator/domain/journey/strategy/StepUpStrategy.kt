package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.declineTool
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.toAuthAbortMessage
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.policy.EvidenceAxis
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolOutcome

/**
 * Raise the level of an already authenticated session (docs/journeys/step-up.md). When no active
 * method can close the gap, the `RE_IDENTIFY` sub-journey asks before any fresh identification.
 */
class StepUpStrategy : IntentStrategy<StepUpState> {

    /** What both states carry through the whole run, so the helpers take it as one value. */
    private data class Goal(
        val targetAcr: AcrLevel,
        val startingAcr: AcrLevel,
        val allowReIdentification: Boolean,
        val reason: StepUpState.Reason?
    )

    private val StepUpState.Start.goal get() = Goal(targetAcr, startingAcr, allowReIdentification, reason)
    private val StepUpState.AuthChoice.goal get() = Goal(targetAcr, startingAcr, allowReIdentification, reason)

    override val intent = AuthIntent.STEP_UP

    /** Never entered without a target; only reachable as a sub-journey, which seeds the real one. */
    override fun initialState(ctx: JourneyContext): StepUpState = StepUpState.Start(ctx.acrFloor, startingAcr = AcrLevel.NONE)

    override fun transition(state: StepUpState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            // Nothing is activatable here, so no Completed event arrives.
            is StepUpState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> finishOrContinue(state.goal, ctx)
                // No new evidence - re-deriving would just re-request the same RE_IDENTIFY again.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> offerAuth(state.goal, ctx)
            }

            is StepUpState.AuthChoice -> when (event) {
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) {
                    offerReIdentOrGiveUp(state.goal, ctx, whenNone = Transition.Cancel)
                }
                // ActionCompleted: re-check with the fresh, post-proof context.
                else -> finishOrContinue(state.goal, ctx)
            }
        }

    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome)
        is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
            event.notOffered("STEP_UP")
    }

    private fun finishOrContinue(goal: Goal, ctx: JourneyContext): Transition =
        if (ctx.policy.isSatisfied(ctx.evidence, goal.targetAcr, ctx.requireAccount())) Transition.Authenticated
        else offerAuth(goal, ctx)

    private fun offerAuth(goal: Goal, ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        val candidates = CandidateTools.forAuth(account, goal.targetAcr, ctx)
        if (candidates.isNotEmpty()) {
            // Authenticator evidence already on the channel means this offer asks for an
            // additional factor (StepUpState.AuthChoice.additionalFactorRound).
            val additionalFactorRound = ctx.evidence.methods.any { it.axis == EvidenceAxis.AUTHENTICATOR }
            return Transition.To(
                StepUpState.AuthChoice(
                    goal.targetAcr, goal.startingAcr, Offer(candidates), goal.allowReIdentification,
                    reason = goal.reason, additionalFactorRound = additionalFactorRound
                )
            )
        }
        return offerReIdentOrGiveUp(
            goal, ctx,
            whenNone = Transition.Abort(ctx.policy.reachability(account, goal.targetAcr).toAuthAbortMessage())
        )
    }

    /**
     * Offers re-identification if allowed ([StepUpState.Start.allowReIdentification]) and able to
     * close the gap; [whenNone] otherwise.
     */
    private fun offerReIdentOrGiveUp(goal: Goal, ctx: JourneyContext, whenNone: Transition): Transition =
        if (goal.allowReIdentification && CandidateTools.forReIdentification(goal.targetAcr, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(
                AuthIntent.RE_IDENTIFY,
                // ctx.currentAcr, not the possibly stale startingAcr of this run.
                seedWith = ReIdentifyState.forSubJourney(goal.targetAcr, ctx.currentAcr),
                resumeWith = StepUpState.Start(goal.targetAcr, goal.startingAcr)
            )
        } else {
            whenNone
        }
}
