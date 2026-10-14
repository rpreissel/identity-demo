package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.DeviceLinkQuestion
import com.example.identity.core.orchestrator.domain.journey.state.AnswerableState
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState

/** An answer outside [com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT]/`ANSWER_DECLINE`. */
internal fun JourneyEvent.Answered.notUnderstood(state: String): Nothing = error("$state does not understand answer '$answer'")

/** True when this device is durably linked to an account other than the one in hand (docs/04-orchestrierung.md #2, "Zweitaccount"). */
internal fun JourneyContext.deviceLinkedElsewhere(): Boolean = linkedAccountId != null && linkedAccountId != account?.accountId

/**
 * Answering a device-link question ([DeviceLinkQuestion], or its "Enrollment zuerst" twin):
 * accepting links the device (`Action.LinkDevice`) and resumes here, after which [afterwards]
 * applies; declining leads to [whenDeclined]. Which is each journey's: a login simply finishes, a
 * registration gives up.
 */
internal inline fun AnswerableState.deviceLinkAnswered(event: JourneyEvent, whenDeclined: () -> Transition, afterwards: () -> Transition): Transition = when (event) {
    is JourneyEvent.Answered -> when (event.answer) {
        ANSWER_ACCEPT -> Transition.Perform(Action.LinkDevice, resumeState = this)
        ANSWER_DECLINE -> whenDeclined()
        else -> event.notUnderstood(this::class.simpleName!!)
    }
    is JourneyEvent.ActionCompleted -> afterwards()
    else -> error("${this::class.simpleName} only accepts JourneyEvent.Answered")
}

/**
 * `RE_IDENTIFY` toward [targetAcr] when an identification can reach it on this channel
 * ([CandidateTools.forReIdentification]), [whenNone] otherwise. [resumeWith] is where the asking
 * journey carries on once the sub-journey returns; [wording] only where the default ("erneut",
 * "nicht erreichbar") is wrong; [optional] as in [Transition.RequireSubJourney].
 */
internal inline fun reIdentifyOr(
    targetAcr: AcrLevel,
    ctx: JourneyContext,
    resumeWith: JourneyState,
    wording: ReIdentifyState.Wording? = null,
    optional: Boolean = false,
    whenNone: () -> Transition
): Transition =
    if (CandidateTools.forReIdentification(targetAcr, ctx).isNotEmpty()) {
        Transition.RequireSubJourney(AuthIntent.RE_IDENTIFY, seedWith = ReIdentifyState.forSubJourney(targetAcr, wording), resumeWith = resumeWith, optional = optional)
    } else {
        whenNone()
    }
