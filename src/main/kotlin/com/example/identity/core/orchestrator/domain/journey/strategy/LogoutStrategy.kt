package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.LogoutState

/**
 * Logout as a journey: a single confirmation prompt, then logout on accept
 * (docs/journeys/logout.md).
 */
class LogoutStrategy : IntentStrategy<LogoutState> {

    override val intent = AuthIntent.LOGOUT

    override fun initialState(ctx: JourneyContext): LogoutState = LogoutState.ConfirmPending

    override fun transition(state: LogoutState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is LogoutState.ConfirmPending -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ANSWER_ACCEPT -> Transition.Logout
                    ANSWER_DECLINE -> Transition.Cancel
                    else -> event.notUnderstood("ConfirmPending")
                }
                else -> Transition.To(state)
            }
        }
}
