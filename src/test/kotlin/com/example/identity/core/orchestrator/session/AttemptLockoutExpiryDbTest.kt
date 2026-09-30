package com.example.identity.core.orchestrator.session

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.UUID

/**
 * A lockout that has run out starts the count over. Otherwise one wrong guess per lockout period
 * would keep a stranger's account locked for good (docs/07-betrieb.md #4). Against the real
 * statement, since the rule lives in one UPDATE.
 */
@SpringBootTest
@ActiveProfiles("test")
class AttemptLockoutExpiryDbTest(
    private val rateLimitCounter: RateLimitCounter,
    private val repository: RateLimitRecordRepository,
) : BehaviorSpec({

    fun fail(subject: String, lockout: Duration) =
        rateLimitCounter.recordFailure(RateLimitScope.ACCOUNT, subject, maxFailures = 2, lockout = lockout)

    given("a subject whose lockout has already run out") {
        val subject = "expired-" + UUID.randomUUID()
        // A negative lockout places lockedUntil in the past: tripped, and already over.
        repeat(2) { fail(subject, Duration.ofMinutes(-1)) }

        `when`("it fails once more") {
            fail(subject, Duration.ofMinutes(15))

            then("the count starts over at one and no lock is set") {
                repository.findFailedCount(RateLimitScope.ACCOUNT.name, subject) shouldBe 1
                rateLimitCounter.isLocked(RateLimitScope.ACCOUNT, subject) shouldBe false
            }
        }
    }

    given("a subject whose lockout is still running") {
        val subject = "running-" + UUID.randomUUID()
        repeat(2) { fail(subject, Duration.ofMinutes(15)) }

        `when`("it fails once more") {
            fail(subject, Duration.ofMinutes(15))

            then("the count goes on and the lock stays") {
                repository.findFailedCount(RateLimitScope.ACCOUNT.name, subject) shouldBe 3
                rateLimitCounter.isLocked(RateLimitScope.ACCOUNT, subject) shouldBe true
            }
        }
    }
})
