package com.example.identity.core.orchestrator.keycloak

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.micrometer.observation.ObservationRegistry
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

    given("the JVM's default TLS setup") {
        val defaultContext = SSLContext.getDefault()
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val defaultSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

        `when`("a Keycloak client that trusts Keycloak's self-signed certificate is built") {
            KeycloakHttp(trustSelfSigned = true, observationRegistry = ObservationRegistry.NOOP).restClient("https://localhost:8543")

            then("the JVM defaults are untouched") {
                SSLContext.getDefault() shouldBeSameInstanceAs defaultContext
                HttpsURLConnection.getDefaultHostnameVerifier() shouldBeSameInstanceAs defaultVerifier
                HttpsURLConnection.getDefaultSSLSocketFactory() shouldBeSameInstanceAs defaultSocketFactory
                System.getProperty("jdk.internal.httpclient.disableHostnameVerification").shouldBeNull()
            }
        }
    }

    given("a Keycloak that accepts the connection but never answers") {
        val silent = ServerSocket(0)
        afterSpec { silent.close() }
        val http = KeycloakHttp(
            trustSelfSigned = false, observationRegistry = ObservationRegistry.NOOP,
            connectTimeout = Duration.ofSeconds(1), readTimeout = Duration.ofMillis(300),
        )

        `when`("the orchestrator calls it") {
            val started = System.nanoTime()
            val result = runCatching { http.getText("http://localhost:${silent.localPort}/jwks") }
            val waited = Duration.ofNanos(System.nanoTime() - started)

            then("the call gives up after the read timeout instead of holding on") {
                result.isFailure shouldBe true
                (waited < Duration.ofSeconds(5)) shouldBe true
            }
        }
    }
})
