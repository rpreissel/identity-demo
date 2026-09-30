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

    override val intent = AuthIntent.STEP_UP

    /** Never entered without a target; only reachable as a sub-journey, which seeds the real one. */
    override fun initialState(ctx: JourneyContext): StepUpState = StepUpState.Start(ctx.acrFloor, startingAcr = AcrLevel.NONE)

    override fun transition(state: StepUpState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            // Nothing is activatable here, so no Completed event arrives.
            is StepUpState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> finishOrContinue(state.targetAcr, state.startingAcr, state.allowReIdentification, state.reason, ctx)
                // No new evidence - re-deriving would just re-request the same RE_IDENTIFY again.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> offerAuth(state.targetAcr, state.startingAcr, state.allowReIdentification, state.reason, ctx)
            }

            is StepUpState.AuthChoice -> when (event) {
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) {
                    offerReIdentOrGiveUp(state.targetAcr, state.startingAcr, state.allowReIdentification, ctx, whenNone = Transition.Cancel)
                }
                // ActionCompleted: re-check with the fresh, post-proof context.
                else -> finishOrContinue(state.targetAcr, state.startingAcr, state.allowReIdentification, state.reason, ctx)
            }
        }

    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome)
        is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
            error("${event.tool.toolId} is not offered by STEP_UP")
    }

    private fun finishOrContinue(targetAcr: AcrLevel, startingAcr: AcrLevel, allowReIdentification: Boolean, reason: StepUpState.Reason?, ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, targetAcr, account)) return Transition.Authenticated
        return offerAuth(targetAcr, startingAcr, allowReIdentification, reason, ctx)
    }

    private fun offerAuth(targetAcr: AcrLevel, startingAcr: AcrLevel, allowReIdentification: Boolean, reason: StepUpState.Reason?, ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        val candidates = CandidateTools.forAuth(account, targetAcr, ctx)
        if (candidates.isNotEmpty()) {
            // Authenticator evidence already on the channel means this offer asks for an
            // additional factor (StepUpState.AuthChoice.additionalFactorRound).
            val additionalFactorRound = ctx.evidence.methods.any { it.axis == EvidenceAxis.AUTHENTICATOR }
            return Transition.To(StepUpState.AuthChoice(targetAcr, startingAcr, Offer(candidates), allowReIdentification, reason = reason, additionalFactorRound = additionalFactorRound))
        }
        return offerReIdentOrGiveUp(
            targetAcr, startingAcr, allowReIdentification, ctx,
            whenNone = Transition.Abort(ctx.policy.reachability(account, targetAcr).toAuthAbortMessage())
        )
    }

    /**
     * Offers re-identification if allowed ([StepUpState.Start.allowReIdentification]) and able to
     * close the gap; [whenNone] otherwise.
     */
    private fun offerReIdentOrGiveUp(targetAcr: AcrLevel, startingAcr: AcrLevel, allowReIdentification: Boolean, ctx: JourneyContext, whenNone: Transition): Transition =
        if (allowReIdentification && CandidateTools.forReIdentification(targetAcr, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(
                AuthIntent.RE_IDENTIFY,
                // ctx.currentAcr, not the possibly stale startingAcr of this run.
                seedWith = ReIdentifyState.forSubJourney(targetAcr, ctx.currentAcr),
                resumeWith = StepUpState.Start(targetAcr, startingAcr)
            )
        } else {
            whenNone
        }
}
