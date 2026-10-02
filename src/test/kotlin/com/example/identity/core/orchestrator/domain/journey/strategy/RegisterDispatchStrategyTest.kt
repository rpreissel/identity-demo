package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.ToolId
import com.example.identity.core.orchestrator.domain.JourneyFeatureFlag
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.LogoutState
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Unit test of [RegisterDispatchStrategy] (docs/journeys/register.md): the feature flag picks the
 * variant for a new journey only, a running journey follows its state type, and a state of
 * another intent is refused.
 */
class RegisterDispatchStrategyTest : BehaviorSpec({

    val strategy = RegisterDispatchStrategy()
    val flagOff = ctx(account = null)
    val flagOn = ctx(account = null).copy(featureFlags = setOf(JourneyFeatureFlag.REGISTER_ENROLL_FIRST))

    given("the enroll-first flag is off") {
        `when`("a new journey is created") {
            val initial = strategy.initialState(flagOff)

            then("it starts the ident-first variant") {
                initial shouldBe RegisterState.Start
            }
        }
    }

    given("the enroll-first flag is on") {
        `when`("a new journey is created") {
            val initial = strategy.initialState(flagOn)

            then("it starts the enroll-first variant") {
                initial shouldBe RegisterEnrollFirstState.EnrollFirstStart
            }
        }
    }

    given("an ident-first journey, the flag switched on meanwhile") {
        `when`("the journey starts") {
            val transition = strategy.transition(RegisterState.Start, JourneyEvent.Started, flagOn)

            then("it follows the state's variant and offers identification, not the email confirmation of enroll-first") {
                transition shouldBe
                    Transition.To(RegisterState.Identifying(Offer(listOf(ToolId("ident-fsc"), ToolId("ident-eid"), ToolId("ident-nect")))))
            }
        }
    }

    given("an enroll-first journey, the flag switched off meanwhile") {
        `when`("the journey starts") {
            val transition = strategy.transition(RegisterEnrollFirstState.EnrollFirstStart, JourneyEvent.Started, flagOff)

            then("it follows the state's variant and offers email confirmation, not the identification of ident-first") {
                transition shouldBe
                    Transition.To(RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(listOf(ToolId("confirm-email")))))
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
