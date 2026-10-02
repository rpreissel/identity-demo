package com.example.identity.core.orchestrator.dpop

import com.example.identity.core.orchestrator.SharedSpringContext
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * Single use against the real table: the primary key IS the replay check, so a second proof with the same
 * `thumbprint:jti` must fail - one after the other, and when several arrive at the same moment.
 */
class DpopReplayProtectionDbTest(private val service: DpopReplayProtectionService) : SharedSpringContext({

    val expiresAt = Instant.now().plusSeconds(300)

    given("a proof that was already accepted") {
        val jti = UUID.randomUUID().toString()
        service.validateAndStore("thumb", jti, expiresAt)

        `when`("the same thumbprint and jti arrive again") {
            val result = runCatching { service.validateAndStore("thumb", jti, expiresAt) }

            then("they are refused") {
                shouldThrow<DpopValidationException> { result.getOrThrow() }
            }
        }
    }

    given("a proof never seen before") {
        val jti = UUID.randomUUID().toString()

        `when`("it is sent eight times at once") {
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(8)
            val accepted = try {
                val results = (1..8).map {
                    pool.submit(Callable {
                        start.await()
                        runCatching { service.validateAndStore("thumb", jti, expiresAt) }.isSuccess
                    })
                }
                start.countDown()
                results.count { it.get() }
            } finally {
                pool.shutdownNow()
            }

            then("exactly one is accepted") {
                accepted shouldBe 1
            }
        }
    }
})
