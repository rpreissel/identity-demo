package com.example.identity.core.orchestrator.kc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.time.Instant

/** With a simulated clock: no real waiting in the test. */
class AwaitReachableTest : BehaviorSpec({

    /** Waits up to 10 seconds in steps of 2; sleeping only moves the simulated clock on. */
    fun awaitReachable(): AwaitReachable {
        var clock = Instant.parse("2026-01-01T00:00:00Z")
        return AwaitReachable(
            timeout = Duration.ofSeconds(10),
            interval = Duration.ofSeconds(2),
            now = { clock },
            sleep = { clock = clock.plus(it) },
        )
    }

    given("a dependency that answers on the third attempt") {
        var calls = 0
        val probe = { if (++calls < 3) error("not there yet") }

        `when`("waiting for it") {
            awaitReachable().await("Keycloak", probe)

            then("it waits until the dependency answers, and no longer") {
                calls shouldBe 3
            }
        }
    }

    given("a dependency that never answers") {
        `when`("waiting for it") {
            val result = runCatching { awaitReachable().await("Keycloak") { error("connection refused") } }

            then("it gives up after the timeout, naming the last cause") {
                val failure = shouldThrow<IllegalStateException> { result.getOrThrow() }
                failure.message shouldContain "nicht erreichbar"
                generateSequence<Throwable>(failure) { it.cause }.last().message shouldBe "connection refused"
            }
        }
    }
})
