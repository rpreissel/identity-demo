package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.core.account.SignInLog
import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration

/**
 * Unit test of [AccountLockoutService] with the counter and the sign-in log mocked. Checks the
 * ACCOUNT scope, the booking before a check, and the logging of a failure and its lockout.
 */
class AccountLockoutServiceTest : BehaviorSpec({

    val accountId = AccountId(7L)
    val key = "7"

    given("an account the counter reports as locked") {
        val counter = mockk<RateLimitCounter>()
        every { counter.isLocked(RateLimitScope.ACCOUNT, key) } returns true
        val service = AccountLockoutService(counter, mockk(relaxed = true), clock = TEST_CLOCK)

        `when`("asking whether it is locked") {
            service.isLocked(accountId)

            then("it asks the ACCOUNT scope, keyed by the account id") {
                verify { counter.isLocked(RateLimitScope.ACCOUNT, key) }
            }
        }

        `when`("asserting that it is not locked") {
            val result = runCatching { service.assertNotLocked(accountId) }

            then("it refuses with ACCOUNT_LOCKED") {
                val e = shouldThrow<OrchestratorException> { result.getOrThrow() }
                e.code shouldBe ErrorCode.ACCOUNT_LOCKED
            }
        }
    }

    given("an account the counter reports as not locked") {
        val counter = mockk<RateLimitCounter>()
        every { counter.isLocked(RateLimitScope.ACCOUNT, key) } returns false
        val service = AccountLockoutService(counter, mockk(relaxed = true), clock = TEST_CLOCK)

        `when`("asserting that it is not locked") {
            val result = runCatching { service.assertNotLocked(accountId) }

            then("it passes") {
                result.isSuccess shouldBe true
            }
        }
    }

    given("an account the counter admits one more attempt for") {
        val counter = mockk<RateLimitCounter>()
        every { counter.admitAttempt(RateLimitScope.ACCOUNT, key, any(), any()) } returns true
        justRun { counter.refundAttempt(RateLimitScope.ACCOUNT, key, any()) }
        val service = AccountLockoutService(counter, mockk(relaxed = true), clock = TEST_CLOCK)

        `when`("an attempt is booked") {
            val admitted = service.admitAttempt(accountId)

            then("it books with the service's limit and lockout duration") {
                admitted shouldBe true
                verify(exactly = 1) { counter.admitAttempt(RateLimitScope.ACCOUNT, key, 5, Duration.ofMinutes(15)) }
            }
        }

        `when`("a booked attempt that checked nothing is given back") {
            service.refund(accountId)

            then("it takes it back under the same limit") {
                verify(exactly = 1) { counter.refundAttempt(RateLimitScope.ACCOUNT, key, 5) }
            }
        }
    }

    given("an account the counter admits no attempt for") {
        val counter = mockk<RateLimitCounter>()
        every { counter.admitAttempt(RateLimitScope.ACCOUNT, key, any(), any()) } returns false
        val service = AccountLockoutService(counter, mockk(relaxed = true), clock = TEST_CLOCK)

        `when`("a known-account tool asks for an attempt") {
            val result = runCatching { service.requireAttempt(accountId) }

            then("it refuses with ACCOUNT_LOCKED") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.ACCOUNT_LOCKED
            }
        }
    }

    given("a booked attempt whose failure tripped the lock") {
        val counter = mockk<RateLimitCounter>()
        val signInLog = mockk<SignInLog>(relaxed = true)
        val lockedUntil = TEST_NOW.plus(Duration.ofMinutes(15))
        every { counter.lockedUntil(RateLimitScope.ACCOUNT, key) } returns lockedUntil
        val service = AccountLockoutService(counter, signInLog, clock = TEST_CLOCK)

        `when`("the failure is recorded") {
            service.recordFailure(accountId, "APP", "sms", "auth-sms@1")

            then("it does not count again: the booking counted") {
                verify(exactly = 0) { counter.recordFailure(any(), any(), any(), any()) }
            }

            then("it logs the failed sign-in") {
                verify(exactly = 1) { signInLog.signInFailed(accountId, "APP", "sms", "auth-sms@1") }
            }

            then("it logs the lockout") {
                verify(exactly = 1) { signInLog.lockedOut(accountId, "APP", lockedUntil) }
            }
        }
    }

    given("a booked attempt whose failure stays below the limit") {
        val counter = mockk<RateLimitCounter>()
        val signInLog = mockk<SignInLog>(relaxed = true)
        every { counter.lockedUntil(RateLimitScope.ACCOUNT, key) } returns TEST_NOW.minus(Duration.ofMinutes(1))
        val service = AccountLockoutService(counter, signInLog, clock = TEST_CLOCK)

        `when`("the failure is recorded") {
            service.recordFailure(accountId, "WEB", "password", "auth-password@1")

            then("it logs the failed sign-in and no lockout, an old lock included") {
                verify(exactly = 1) { signInLog.signInFailed(accountId, "WEB", "password", "auth-password@1") }
                verify(exactly = 0) { signInLog.lockedOut(any(), any(), any()) }
            }
        }
    }

    given("an account with counted failures") {
        val counter = mockk<RateLimitCounter>()
        justRun { counter.reset(RateLimitScope.ACCOUNT, key) }
        val service = AccountLockoutService(counter, mockk(relaxed = true), clock = TEST_CLOCK)

        `when`("a success is recorded") {
            service.recordSuccess(accountId)

            then("it resets the ACCOUNT counter") {
                verify(exactly = 1) { counter.reset(RateLimitScope.ACCOUNT, key) }
            }
        }
    }
})
