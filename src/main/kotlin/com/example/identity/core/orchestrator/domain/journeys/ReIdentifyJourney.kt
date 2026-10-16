package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.journey.JourneyStart
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.OnlyAsSubJourney
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.Answer
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState

/**
 * "No active method reaches the target, re-identify instead?" (docs/journeys/re-identify.md).
 * Only reached as a sub-journey. What the identification may change is decided by
 * [Action.RecordIdentification]'s handler, so no caller can smuggle in someone else's identity.
 */
object ReIdentifyJourney : Journey<ReIdentifyState> {

    override val name = "RE_IDENTIFY"
    override val startPoints = listOf(OnlyAsSubJourney)
    override val stateRoots = listOf(ReIdentifyState::class)

    /** A fresh identification to reach [targetAcr], asked first in [wording] (the default question if null). */
    fun toReach(targetAcr: AcrLevel, wording: ReIdentifyState.Wording? = null): JourneyStart<*> =
        JourneyStart(this, ReIdentifyState.OfferReIdent(targetAcr, wording))

    override fun transition(state: ReIdentifyState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is ReIdentifyState.OfferReIdent -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    Answer.ACCEPT -> offerIdentifying(state, ctx) ?: Transition.Cancel
                    Answer.DECLINE -> Transition.Cancel
                }
                // Started: always present the prompt, unconditionally.
                else -> Transition.To(state)
            }

            is ReIdentifyState.Identifying -> when (event) {
                is JourneyEvent.OfferExhausted -> Transition.Cancel
                // Identity confirmed - this identification's own maxAcr already IS the achieved level.
                else -> Transition.Authenticated
            }
        }

    /** Null when no identification tool can reach the target (anymore). */
    private fun offerIdentifying(offer: ReIdentifyState.OfferReIdent, ctx: JourneyContext): Transition? =
        CandidateTools.forReIdentification(offer.targetAcr, ctx).takeIf { it.isNotEmpty() }
            ?.let { Transition.To(ReIdentifyState.Identifying(offer.targetAcr, Offer(it), wording = offer.wording)) }
}
