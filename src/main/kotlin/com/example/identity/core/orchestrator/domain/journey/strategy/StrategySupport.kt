package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState

/**
 * On a mandatory state, backing out of a tool is not declining it: the obligation stands, so the
 * full choice comes back. Only fallback states accumulate `declined`.
 */
internal fun reoffer(state: JourneyState): Transition = Transition.To(state.withActive(null))

/** A tool completed that this intent never offers: a broken state machine, not a user error. */
internal fun JourneyEvent.Completed.notOffered(by: String): Nothing = error("${tool.toolId} is not offered by $by")

/** An answer outside [com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT]/`ANSWER_DECLINE`. */
internal fun JourneyEvent.Answered.notUnderstood(state: String): Nothing = error("$state does not understand answer '$answer'")
