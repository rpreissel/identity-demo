package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.offer
import com.example.identity.core.orchestrator.domain.journey.reIdentificationCandidates
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.journey.JourneyStart
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.OnlyAsSubJourney
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.Answer
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.notExpectedIn
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.ToolFreeState

/**
 * "No active method reaches the target, re-identify instead?" (docs/journeys/re-identify.md).
 * Only reached as a sub-journey. What the identification may change is decided by
 * [Action.RecordIdentification]'s handler, so no caller can smuggle in someone else's identity.
 */
object ReIdentifyJourney : Journey<ReIdentifyState> {

    override val name = "RE_IDENTIFY"
    override val startPoints = listOf(OnlyAsSubJourney)
    override val stateRoot = ReIdentifyState::class

    /** A fresh identification because nothing else reaches [targetAcr]. */
    fun toReach(targetAcr: AcrLevel): JourneyStart<*> =
        JourneyStart(this, ReIdentifyState.OfferReIdent(targetAcr, ReIdentifyState.Kind.RECOVERY))

    /**
     * [toReach] when an identification can reach [targetAcr] on this channel, [whenNone] otherwise.
     * [resumeWith] is where the asking journey carries on once the sub-journey returns. A required
     * sub-journey: declining it cancels the asking journey.
     */
    internal inline fun toReachOr(
        targetAcr: AcrLevel,
        ctx: JourneyContext,
        resumeWith: ToolFreeState,
        whenNone: () -> Transition
    ): Transition =
        if (ctx.reIdentificationCandidates(targetAcr).isNotEmpty()) Transition.RequireSubJourney(toReach(targetAcr), resumeWith = resumeWith)
        else whenNone()

    /** Identification as an optional extra for a never-identified account; request it as optional. */
    fun offerOptionally(targetAcr: AcrLevel): JourneyStart<*> =
        JourneyStart(this, ReIdentifyState.OfferReIdent(targetAcr, ReIdentifyState.Kind.OPTIONAL))

    override fun transition(state: ReIdentifyState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is ReIdentifyState.OfferReIdent -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    Answer.ACCEPT -> offerIdentifying(state.targetAcr, state.kind, ctx)
                    Answer.DECLINE -> Transition.Cancel
                }
                // Always present the prompt, unconditionally.
                JourneyEvent.Started -> Transition.To(state)
                else -> event.notExpectedIn(state)
            }

            is ReIdentifyState.Identifying -> when (event) {
                // The identification is booked. It may have proven less than offered for (Nect
                // reports its level per procedure); then the remaining identifications are offered.
                JourneyEvent.ActionCompleted ->
                    if (ctx hasReached state.targetAcr) Transition.Authenticated
                    else offerIdentifying(state.targetAcr, state.kind, ctx)
                is JourneyEvent.OfferExhausted -> Transition.Cancel
                else -> event.notExpectedIn(state)
            }
        }

    /** The identifications that can still reach [targetAcr]; giving up when none is left. */
    private fun offerIdentifying(targetAcr: AcrLevel, kind: ReIdentifyState.Kind, ctx: JourneyContext): Transition =
        offer(ctx.reIdentificationCandidates(targetAcr)) { ReIdentifyState.Identifying(targetAcr, it, kind = kind) } ?: Transition.Cancel
}
