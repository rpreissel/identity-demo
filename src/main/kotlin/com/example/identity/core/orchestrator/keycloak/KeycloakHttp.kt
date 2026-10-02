package com.example.identity.core.orchestrator.keycloak

import io.micrometer.observation.ObservationRegistry
import java.net.HttpURLConnection
import java.time.Duration
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * The one place the orchestrator's HTTP clients toward Keycloak come from. Whether Keycloak's
 * certificate is checked belongs to the setup variant (`trustSelfSignedCertificate`, for the local
 * self-signed certificate). The exception applies only to these clients, never JVM-wide, so other
 * outgoing HTTPS calls keep their checks.
 */
@Component
@Profile("keycloak")
class KeycloakHttp(
    @Value("\${keycloak-tls.trust-self-signed}") val trustSelfSigned: Boolean,
    // Times every call as http.client.requests, the Keycloak latency of docs/07-betrieb.md Abschnitt 7.
    private val observationRegistry: ObservationRegistry,
    // Bounded, because some calls run inside a journey's transaction (ADR-43): a Keycloak that
    // accepts but never answers must not hold those row locks indefinitely.
    @Value("\${keycloak-http.connect-timeout:3s}") private val connectTimeout: Duration = Duration.ofSeconds(3),
    @Value("\${keycloak-http.read-timeout:10s}") private val readTimeout: Duration = Duration.ofSeconds(10),
) {
    init {
        if (trustSelfSigned) {
            log.warn(
                "Keycloak certificate is NOT verified (keycloak-setup trustSelfSignedCertificate=true) - " +
                    "only for Keycloak connections, only for a self-signed development certificate"
            )
        }
    }

    private val requestFactory = (if (trustSelfSigned) TrustingRequestFactory(trustAllSocketFactory()) else SimpleClientHttpRequestFactory())
        .apply {
            setConnectTimeout(connectTimeout)
            setReadTimeout(readTimeout)
        }

    /** A [RestClient] against [baseUrl], with this setup's certificate policy. */
    fun restClient(baseUrl: String): RestClient =
        RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).observationRegistry(observationRegistry).build()

    /** GETs [url] as text, with this setup's certificate policy - for the JWKS fetch. */
    fun getText(url: String): String =
        RestClient.builder().requestFactory(requestFactory).observationRegistry(observationRegistry).build()
            .get().uri(url).retrieve().body<String>()
            ?: error("Empty response from $url")

    /** Per-connection trust exception - never touches the JVM defaults. */
    private class TrustingRequestFactory(private val socketFactory: SSLSocketFactory) : SimpleClientHttpRequestFactory() {
        override fun prepareConnection(connection: HttpURLConnection, httpMethod: String) {
            if (connection is HttpsURLConnection) {
                connection.sslSocketFactory = socketFactory
                connection.setHostnameVerifier { _, _ -> true }
            }
            super.prepareConnection(connection, httpMethod)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(KeycloakHttp::class.java)

        fun trustAllSocketFactory(): SSLSocketFactory {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS")
                .apply { init(null, arrayOf<TrustManager>(trustAll), SecureRandom()) }
                .socketFactory
        }
    }
}
