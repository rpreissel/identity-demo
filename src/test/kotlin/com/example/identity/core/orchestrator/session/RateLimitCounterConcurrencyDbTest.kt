package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.SharedSpringContext
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Failed attempts arriving at the same moment on a counter that does not exist yet: each one counts. The row
 * is created by whichever request comes first; the others must neither fail nor reset it to zero. Attempts
 * booked before their check get through only up to the limit (docs/07-betrieb.md #4).
 */
class RateLimitCounterConcurrencyDbTest(
    private val rateLimitCounter: RateLimitCounter,
    private val repository: RateLimitRecordRepository,
) : SharedSpringContext({

    given("a subject without a counter yet") {
        val subject = "concurrency-" + UUID.randomUUID()

        `when`("eight failed attempts arrive at once") {
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            val results = try {
                val futures = (1..8).map {
                    pool.submit(Callable {
                        start.await()
                        runCatching { rateLimitCounter.recordFailure(RateLimitScope.ACCOUNT, subject, maxFailures = 100, lockout = Duration.ofMinutes(15)) }
                    })
                }
                start.countDown()
                futures.map { it.get() }
            } finally {
                pool.shutdownNow()
            }

            then("none of them fails") {
                results.filter { it.isFailure }.map { it.exceptionOrNull()?.toString() } shouldBe emptyList()
            }
            then("all eight are counted") {
                repository.findFailedCount(RateLimitScope.ACCOUNT.name, subject) shouldBe 8
            }
        }
    }

    given("a subject without a counter yet and a limit of five") {
        val subject = "admission-" + UUID.randomUUID()

        `when`("eight attempts ask to be admitted at once") {
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            val admitted = try {
                val futures = (1..8).map {
                    pool.submit(Callable {
                        start.await()
                        rateLimitCounter.admitAttempt(RateLimitScope.ACCOUNT, subject, maxFailures = 5, lockout = Duration.ofMinutes(15))
                    })
                }
                start.countDown()
                futures.map { it.get() }
            } finally {
                pool.shutdownNow()
            }

            then("exactly five get through, so parallelism buys no extra guesses") {
                admitted.count { it } shouldBe 5
                repository.findFailedCount(RateLimitScope.ACCOUNT.name, subject) shouldBe 5
            }
        }
    }

    given("a subject whose fifth booked attempt tripped the lock") {
        val subject = "refund-" + UUID.randomUUID()
        repeat(5) { rateLimitCounter.admitAttempt(RateLimitScope.ACCOUNT, subject, maxFailures = 5, lockout = Duration.ofMinutes(15)) }

        `when`("that attempt checked nothing and is given back") {
            rateLimitCounter.refundAttempt(RateLimitScope.ACCOUNT, subject, maxFailures = 5)

            then("the lock goes with it and four attempts stay counted") {
                rateLimitCounter.isLocked(RateLimitScope.ACCOUNT, subject) shouldBe false
                repository.findFailedCount(RateLimitScope.ACCOUNT.name, subject) shouldBe 4
            }
        }
    }
})
