package com.example.identity.core.orchestrator.kc

import io.micrometer.observation.ObservationRegistry
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.net.ServerSocket
import java.time.Duration
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

/**
 * Trusting Keycloak's self-signed certificate must stay an exception of the Keycloak clients built by
 * [KeycloakHttp] - it must never replace the JVM-wide default SSLContext and hostname verifier, which
 * would disable certificate checks for every outgoing call.
 */
class KeycloakHttpTest : BehaviorSpec({

    given("a setup that trusts Keycloak's self-signed certificate") {
        val defaultContext = SSLContext.getDefault()
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val defaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

        val http = KeycloakHttp(trustSelfSigned = true, observationRegistry = ObservationRegistry.NOOP)
        http.restClient("https://localhost:8543")

        then("the JVM defaults are untouched") {
            SSLContext.getDefault() shouldBeSameInstanceAs defaultContext
            HttpsURLConnection.getDefaultHostnameVerifier() shouldBeSameInstanceAs defaultVerifier
            HttpsURLConnection.getDefaultSSLSocketFactory() shouldBeSameInstanceAs defaultSocketFactory
            System.getProperty("jdk.internal.httpclient.disableHostnameVerification") shouldBe null
        }
    }

    given("a Keycloak that accepts the connection but never answers") {
        val silent = ServerSocket(0)
        val http = KeycloakHttp(
            trustSelfSigned = false, observationRegistry = ObservationRegistry.NOOP,
            connectTimeout = Duration.ofSeconds(1), readTimeout = Duration.ofMillis(300),
        )

        `when`("the orchestrator calls it") {
            val started = System.nanoTime()
            val result = runCatching { http.getText("http://localhost:${silent.localPort}/jwks") }
            val waited = Duration.ofNanos(System.nanoTime() - started)
            silent.close()

            then("the call gives up after the read timeout instead of holding on") {
                result.isFailure shouldBe true
                (waited < Duration.ofSeconds(5)) shouldBe true
            }
        }
    }
})
