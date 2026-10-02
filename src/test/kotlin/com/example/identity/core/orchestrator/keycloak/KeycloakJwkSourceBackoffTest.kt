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
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

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
})
