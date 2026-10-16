package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.Answer
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.DeviceLinkQuestion

/**
 * The answer to a [DeviceLinkQuestion]: accepting links the device (`Action.LinkDevice`) and
 * resumes here, after which [afterwards] applies; declining leads to [whenDeclined]. Which is each
 * journey's: a login simply finishes, a registration gives up.
 */
internal inline fun DeviceLinkQuestion.answered(event: JourneyEvent, whenDeclined: () -> Transition, afterwards: () -> Transition): Transition = when (event) {
    is JourneyEvent.Answered -> when (event.answer) {
        Answer.ACCEPT -> Transition.Perform(Action.LinkDevice, resumeState = this)
        Answer.DECLINE -> whenDeclined()
    }
    is JourneyEvent.ActionCompleted -> afterwards()
    else -> error("DeviceLinkQuestion only accepts JourneyEvent.Answered")
}
