package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.declineTool
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolOutcome

/**
 * "No active method reaches the target, re-identify instead?" (docs/journeys/re-identify.md).
 * Only reached as a sub-journey. What the identification may change is decided by
 * [Action.RecordIdentification]'s handler, so no caller can smuggle in someone else's identity.
 */
class ReIdentifyStrategy : IntentStrategy<ReIdentifyState> {

    override val intent = AuthIntent.RE_IDENTIFY

    /** Never entered without a target; only reachable as a sub-journey, which seeds the real one. */
    override fun initialState(ctx: JourneyContext): ReIdentifyState = ReIdentifyState.OfferReIdent(ctx.acrFloor, startingAcr = AcrLevel.NONE)

    override fun transition(state: ReIdentifyState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is ReIdentifyState.OfferReIdent -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ANSWER_ACCEPT -> offerIdentifying(state, ctx) ?: Transition.Cancel
                    ANSWER_DECLINE -> Transition.Cancel
                    else -> event.notUnderstood("OfferReIdent")
                }
                // Started: always present the prompt, unconditionally.
                else -> Transition.To(state)
            }

            is ReIdentifyState.Identifying -> when (event) {
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) { Transition.Cancel }
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                // Identity confirmed - this identification's own maxAcr already IS the achieved level.
                else -> Transition.Authenticated
            }
        }

    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Identified -> Action.RecordIdentification(event.tool, outcome)
        is ToolOutcome.Completed.Authenticated, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
            event.notOffered("RE_IDENTIFY")
    }

    /** Null when no identification tool can reach the target (anymore). */
    private fun offerIdentifying(offer: ReIdentifyState.OfferReIdent, ctx: JourneyContext): Transition? =
        CandidateTools.forReIdentification(offer.targetAcr, ctx).takeIf { it.isNotEmpty() }
            ?.let { Transition.To(ReIdentifyState.Identifying(offer.targetAcr, offer.startingAcr, Offer(it), wording = offer.wording)) }
}
