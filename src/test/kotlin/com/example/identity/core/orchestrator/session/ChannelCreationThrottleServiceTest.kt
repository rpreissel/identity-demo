package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration

/**
 * Unit test of [ChannelCreationThrottleService] with the counter mocked: a channel opening is
 * counted per binding key in a rolling window, and one over budget is refused with 429.
 */
class ChannelCreationThrottleServiceTest : BehaviorSpec({

    val bindingKeyRef = "binding-key-1"

    given("a binding key still within its budget") {
        val counter = mockk<AttemptCounter>()
        every { counter.recordWindowedAttempt(ThrottleScope.BINDING_KEY, bindingKeyRef, any<Int>(), any<Duration>()) } returns true
        val service = ChannelCreationThrottleService(counter)

        `when`("a channel is opened") {
            val result = runCatching { service.recordAndAssertWithinBudget(bindingKeyRef) }

            then("it passes") {
                result.isSuccess shouldBe true
            }

            then("it counts the attempt in the BINDING_KEY scope, 20 per 5 minutes") {
                verify(exactly = 1) { counter.recordWindowedAttempt(ThrottleScope.BINDING_KEY, bindingKeyRef, 20, Duration.ofMinutes(5)) }
            }
        }
    }

    given("a binding key over its budget") {
        val counter = mockk<AttemptCounter>()
        every { counter.recordWindowedAttempt(ThrottleScope.BINDING_KEY, bindingKeyRef, any<Int>(), any<Duration>()) } returns false
        val service = ChannelCreationThrottleService(counter)

        `when`("a channel is opened") {
            val result = runCatching { service.recordAndAssertWithinBudget(bindingKeyRef) }

            then("it refuses with TOO_MANY_REQUESTS") {
                val e = shouldThrow<OrchestratorException> { result.getOrThrow() }
                e.code shouldBe ErrorCode.TOO_MANY_REQUESTS
            }
        }
    }
})
