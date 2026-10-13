package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState

/**
 * On a mandatory state, backing out of a tool is not declining it: the obligation stands, so the
 * full choice comes back. Only fallback states accumulate `declined`.
 */
internal fun reoffer(state: JourneyState): Transition = Transition.To(state.withActive(null))

/** The action this completed tool stands for ([Action.of]); `admittedAt` only for a change in place. */
internal fun JourneyEvent.Completed.action(admittedAt: AcrLevel? = null): Action = Action.of(tool, outcome, admittedAt)

/** A tool completed that this state never acts on: a broken state machine, not a user error. */
internal fun JourneyEvent.Completed.notOffered(by: String): Nothing = error("${tool.toolId} is not offered by $by")

/** An answer outside [com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT]/`ANSWER_DECLINE`. */
internal fun JourneyEvent.Answered.notUnderstood(state: String): Nothing = error("$state does not understand answer '$answer'")
