package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.FeatureFlags
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.state.LogoutState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Unit test of [RegisterDispatchStrategy] (docs/journeys/register.md): the feature flag picks the
 * variant for a new journey only, a running journey follows its state type, and a state of
 * another intent is refused.
 */
class RegisterDispatchStrategyTest : BehaviorSpec({

    val strategy = RegisterDispatchStrategy()
    val flagOff = ctx(account = null)
    val flagOn = ctx(account = null).copy(featureFlags = setOf(FeatureFlags.REGISTER_ENROLL_FIRST))

    given("the intent") {
        then("is REGISTER") {
            strategy.intent shouldBe AuthIntent.REGISTER
        }
    }

    given("initialState without the enroll-first flag") {
        then("starts the ident-first variant") {
            strategy.initialState(flagOff) shouldBe RegisterState.Start
        }
    }

    given("initialState with the enroll-first flag") {
        then("starts the enroll-first variant") {
            strategy.initialState(flagOn) shouldBe RegisterEnrollFirstState.EnrollFirstStart
        }
    }

    given("an ident-first journey, the flag switched on meanwhile") {
        `when`("the journey starts") {
            val transition = strategy.transition(RegisterState.Start, JourneyEvent.Started, flagOn)

            then("it follows the state's variant, RegisterStrategy, not the flag") {
                transition shouldBe RegisterStrategy().transition(RegisterState.Start, JourneyEvent.Started, flagOn)
                transition shouldNotBe RegisterEnrollFirstStrategy().transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, flagOn)
            }
        }
    }

    given("an enroll-first journey, the flag switched off meanwhile") {
        `when`("the journey starts") {
            val transition = strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, flagOff)

            then("it follows the state's variant, RegisterEnrollFirstStrategy, not the flag") {
                transition shouldBe RegisterEnrollFirstStrategy().transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, flagOff)
                transition shouldNotBe RegisterStrategy().transition(RegisterState.Start, JourneyEvent.Started, flagOff)
            }
        }
    }

    given("a state of another intent") {
        `when`("an event arrives") {
            val result = runCatching { strategy.transition(LogoutState.ConfirmPending, JourneyEvent.Started, flagOff) }

            then("it refuses the foreign state with IllegalStateException") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }
})
