package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.LogoutState
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.account
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.ctx
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.method
import com.example.identity.contract.tool_api.claims.AcrLevel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Unit test of [LogoutStrategy] (docs/journeys/logout.md): one confirmation prompt, accept logs
 * out, decline cancels, and any other event keeps the prompt.
 */
class LogoutStrategyTest : BehaviorSpec({

    val strategy = LogoutStrategy()
    val theCtx = ctx(account = account(method("sms", AcrLevel.LOA1)))

    given("a new journey") {
        `when`("its first state is chosen") {
            val initial = strategy.initialState(theCtx)

            then("it is the confirmation prompt") {
                initial shouldBe LogoutState.ConfirmPending
            }
        }
    }

    given("ConfirmPending") {
        val state = LogoutState.ConfirmPending

        `when`("the user accepts") {
            val transition = strategy.transition(state, JourneyEvent.Answered(ANSWER_ACCEPT), theCtx)

            then("it logs out") {
                transition shouldBe Transition.Logout
            }
        }

        `when`("the user declines") {
            val transition = strategy.transition(state, JourneyEvent.Answered(ANSWER_DECLINE), theCtx)

            then("it cancels, and the channel stays logged in") {
                transition shouldBe Transition.Cancel
            }
        }

        `when`("an unknown answer arrives") {
            val result = runCatching { strategy.transition(state, JourneyEvent.Answered("maybe"), theCtx) }

            then("it fails loudly with IllegalStateException") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("the journey starts") {
            val transition = strategy.transition(state, JourneyEvent.Started, theCtx)

            then("it keeps asking") {
                transition shouldBe Transition.To(LogoutState.ConfirmPending)
            }
        }
    }
})
