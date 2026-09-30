package com.example.identity.core.orchestrator.session

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration

/**
 * Unit test of [PersonLockoutService] with the counter mocked: every call goes to the PERSON
 * scope, keyed by the personId, with the service's own limit and lockout duration.
 */
class PersonLockoutServiceTest : BehaviorSpec({

    val personId = "P000000001"

    given("a person the counter reports as locked") {
        val counter = mockk<RateLimitCounter>()
        every { counter.isLocked(RateLimitScope.PERSON, personId) } returns true
        val service = PersonLockoutService(counter)

        `when`("asking whether it is locked") {
            val locked = service.isLocked(personId)

            then("it answers from the PERSON scope") {
                locked shouldBe true
                verify(exactly = 1) { counter.isLocked(RateLimitScope.PERSON, personId) }
            }
        }
    }

    given("a person with no lock") {
        val counter = mockk<RateLimitCounter>()
        justRun { counter.recordFailure(RateLimitScope.PERSON, personId, any(), any()) }
        val service = PersonLockoutService(counter)

        `when`("a failure is recorded") {
            service.recordFailure(personId)

            then("it counts it in the PERSON scope with five failures and 15 minutes") {
                verify(exactly = 1) { counter.recordFailure(RateLimitScope.PERSON, personId, 5, Duration.ofMinutes(15)) }
            }
        }
    }

    given("a person with counted failures") {
        val counter = mockk<RateLimitCounter>()
        justRun { counter.reset(RateLimitScope.PERSON, personId) }
        val service = PersonLockoutService(counter)

        `when`("a success is recorded") {
            service.recordSuccess(personId)

            then("it resets the PERSON counter") {
                verify(exactly = 1) { counter.reset(RateLimitScope.PERSON, personId) }
            }
        }
    }
})
