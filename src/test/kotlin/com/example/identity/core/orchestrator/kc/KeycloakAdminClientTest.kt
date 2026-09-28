package com.example.identity.core.orchestrator.kc

import com.example.identity.TEST_CLOCK
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nach einem Realm-Neuaufbau im Demomodus ist das zwischengespeicherte Token des Admin-Clients
 * noch nicht abgelaufen, Keycloak nimmt es aber nicht mehr an. Der Client holt dann einmal ein
 * neues, statt bis zum Ablauf jede Kontoloeschung scheitern zu lassen.
 */
class KeycloakAdminClientTest : BehaviorSpec({

    /** Ein Keycloak, das Tokens t1, t2, ... ausstellt und nur die in [accepted] annimmt. */
    class FakeKeycloak(val accepted: Set<String>) {
        val tokensIssued = AtomicInteger()
        val deletes = mutableListOf<String>()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/realms/demo/protocol/openid-connect/token") { ex ->
                val body = """{"access_token":"t${tokensIssued.incrementAndGet()}","expires_in":300}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            createContext("/admin/realms/demo/orchestrator-accounts/") { ex ->
                val token = ex.requestHeaders.getFirst("Authorization").removePrefix("Bearer ")
                deletes += token
                ex.sendResponseHeaders(if (token in accepted) 204 else 401, -1)
                ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
    }

    fun client(keycloak: FakeKeycloak) = KeycloakAdminClient(
        KeycloakHttp(trustSelfSigned = false, observationRegistry = ObservationRegistry.NOOP),
        mockk<OrchestratorClientAssertionSigner> { every { assertionFor(any(), any()) } returns "assertion" },
        baseUrl = keycloak.url, publicBaseUrl = keycloak.url, realm = "demo",
        adminClientId = "orchestrator-admin", appClientId = "orchestrator-app-token", clock = TEST_CLOCK,
    )

    given("ein zwischengespeichertes Token, das Keycloak nach einem Realm-Neuaufbau ablehnt") {
        val keycloak = FakeKeycloak(accepted = setOf("t2"))
        val client = client(keycloak)

        `when`("ein Konto entfernt wird") {
            client.removeAccount(7)

            then("holt der Client ein neues Token und wiederholt den Aufruf einmal") {
                keycloak.tokensIssued.get() shouldBe 2
                keycloak.deletes shouldBe listOf("t1", "t2")
            }
        }
        keycloak.server.stop(0)
    }

    given("ein Keycloak, das auch das frische Token ablehnt") {
        val keycloak = FakeKeycloak(accepted = emptySet())
        val client = client(keycloak)

        `when`("ein Konto entfernt wird") {
            val result = runCatching { client.removeAccount(7) }

            then("scheitert der Aufruf nach genau einer Wiederholung, statt endlos Tokens zu holen") {
                result.isFailure shouldBe true
                keycloak.tokensIssued.get() shouldBe 2
                keycloak.deletes.size shouldBe 2
            }
        }
        keycloak.server.stop(0)
    }
})
