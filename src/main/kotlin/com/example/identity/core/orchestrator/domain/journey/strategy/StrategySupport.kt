package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState

/**
 * A mandatory step: a completed tool is adopted, anything else asks [next] where to go. Backing out
 * never reaches a strategy here ([com.example.identity.core.orchestrator.domain.journey.state.DeclinePolicy.MANDATORY]).
 */
internal inline fun completedOr(state: OfferingState, event: JourneyEvent, next: () -> Transition): Transition = when (event) {
    is JourneyEvent.Completed -> Transition.Perform(event.action(), resumeState = state)
    else -> next()
}

/** The action this completed tool stands for ([Action.of]); `admittedAt` only for a change in place. */
internal fun JourneyEvent.Completed.action(admittedAt: AcrLevel? = null): Action = Action.of(tool, outcome, admittedAt)

/** A tool completed that this state never acts on: a broken state machine, not a user error. */
internal fun JourneyEvent.Completed.notOffered(by: String): Nothing = error("${tool.toolId} is not offered by $by")

/** An answer outside [com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT]/`ANSWER_DECLINE`. */
internal fun JourneyEvent.Answered.notUnderstood(state: String): Nothing = error("$state does not understand answer '$answer'")
