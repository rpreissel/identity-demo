package com.example.identity.core.orchestrator.keycloak

import com.example.identity.TEST_CLOCK
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainOnlyNulls
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Made-up `kid`s must not turn every peer-auth request into a JWKS fetch against Keycloak - an unknown kid
 * refetches at most once per interval.
 */
class KeycloakJwkSourceBackoffTest : BehaviorSpec({

    given("a JWKS endpoint that serves the key 'real' and counts its fetches") {
        val key = ECKeyGenerator(Curve.P_256).keyID("real").generate()
        val fetches = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks") { exchange ->
                fetches.incrementAndGet()
                val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        afterSpec { server.stop(0) }
        val source = KeycloakJwkSource("http://127.0.0.1:${server.address.port}/jwks", cacheTtlSeconds = 600, clock = TEST_CLOCK)

        `when`("a burst of 20 unknown kids is looked up, then the known one") {
            val unknown = (1..20).map { source.find("made-up-$it") }
            val known = source.find("real")

            then("the unknown kids are not found, the known key is") {
                unknown.shouldContainOnlyNulls()
                known.shouldNotBeNull()
            }

            then("the whole burst costs one fetch") {
                fetches.get() shouldBe 1
            }
        }
    }

    given("a JWKS endpoint that fails and counts its fetches") {
        val fetches = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/jwks") { exchange ->
                fetches.incrementAndGet()
                exchange.sendResponseHeaders(503, -1)
                exchange.close()
            }
            start()
        }
        afterSpec { server.stop(0) }
        val source = KeycloakJwkSource("http://127.0.0.1:${server.address.port}/jwks", cacheTtlSeconds = 600, clock = TEST_CLOCK)

        `when`("a burst of 20 lookups arrives") {
            val results = (1..20).map { runCatching { source.find("any") } }

            then("each is rejected as an assertion that cannot be checked, not as a 500") {
                results.forEach { it.exceptionOrNull().shouldBeInstanceOf<PeerAuthValidationException>() }
            }
            then("the burst costs one fetch, not one per request") {
                fetches.get() shouldBe 1
            }
        }
    }

    given("a cached set gone stale, and a Keycloak that is slow to serve the next one") {
        val key = ECKeyGenerator(Curve.P_256).keyID("real").generate()
        val slow = CountDownLatch(1)
        val fetches = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            createContext("/jwks") { exchange ->
                // The first fetch is quick; every later one hangs until the test lets it go.
                if (fetches.incrementAndGet() > 1) slow.await(5, TimeUnit.SECONDS)
                val body = JWKSet(key.toPublicJWK()).toString().toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        afterSpec { slow.countDown(); server.stop(0) }
        var now = Instant.parse("2026-10-13T10:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId?) = this
            override fun instant() = now
        }
        val source = KeycloakJwkSource("http://127.0.0.1:${server.address.port}/jwks", cacheTtlSeconds = 600, clock = clock)
        source.find("real")
        now = now.plusSeconds(601)

        `when`("one lookup starts the slow refetch and a second lookup arrives meanwhile") {
            val first = thread { source.find("real") }
            while (fetches.get() < 2) Thread.sleep(10)
            val started = System.nanoTime()
            val second = source.find("real")
            val waitedMs = (System.nanoTime() - started) / 1_000_000
            slow.countDown()
            first.join()

            then("the second is answered from the stale set at once, without waiting for Keycloak") {
                second.shouldNotBeNull()
                (waitedMs < 1_000) shouldBe true
            }
            then("there was only the one refetch") {
                fetches.get() shouldBe 2
            }
        }
    }
})
