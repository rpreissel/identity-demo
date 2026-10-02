package com.example.identity.core.orchestrator.session

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Failed attempts arriving at the same moment on a counter that does not exist yet: each one counts. The row
 * is created by whichever request comes first; the others must neither fail nor reset it to zero.
 */
@SpringBootTest
@ActiveProfiles("test")
class RateLimitCounterConcurrencyDbTest(
    private val rateLimitCounter: RateLimitCounter,
    private val repository: RateLimitRecordRepository,
) : BehaviorSpec({

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
})
