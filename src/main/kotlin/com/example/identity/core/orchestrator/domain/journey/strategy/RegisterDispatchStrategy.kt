package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.FeatureFlags
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState

/**
 * The one strategy registered for [AuthIntent.REGISTER]: there is one strategy per intent, so it
 * delegates to the two variants, [RegisterStrategy] (ident first) and [RegisterEnrollFirstStrategy]
 * ("Enrollment zuerst"). Generic over [JourneyState], because their state hierarchies do not
 * overlap. The flag comes in through [JourneyContext.featureFlags].
 */
class RegisterDispatchStrategy : IntentStrategy<JourneyState> {

    private val identFirst = RegisterStrategy()
    private val enrollFirst = RegisterEnrollFirstStrategy()

    override val intent: AuthIntent = AuthIntent.REGISTER

    /**
     * The flag is read only here. A running journey's variant follows from its state type, so
     * flipping the flag mid-journey cannot corrupt it.
     */
    override fun initialState(ctx: JourneyContext): JourneyState =
        if (FeatureFlags.REGISTER_ENROLL_FIRST in ctx.featureFlags) enrollFirst.initialState(ctx) else identFirst.initialState(ctx)

    override fun transition(state: JourneyState, event: JourneyEvent, ctx: JourneyContext): Transition = when (state) {
        is RegisterEnrollFirstState -> enrollFirst.transition(state, event, ctx)
        is RegisterState -> identFirst.transition(state, event, ctx)
        else -> error("RegisterDispatchStrategy received a foreign state: ${state::class}")
    }

}
