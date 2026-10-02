package com.example.identity.core.orchestrator.kc

import com.example.identity.TEST_CLOCK
import com.example.identity.contract.tool_api.directory.InvitationEnded
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.kcmigrate.federatedInvitationUserId
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.micrometer.observation.ObservationRegistry
import io.mockk.every
import io.mockk.mockk
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * After a realm rebuild in demo mode the admin client's cached token has not expired yet, but
 * Keycloak no longer accepts it. The client then fetches a new one once, instead of letting every
 * account deletion fail until it expires.
 */
class KeycloakAdminClientTest : BehaviorSpec({

    /** A Keycloak that issues tokens t1, t2, ... and accepts only those in [accepted]. */
    class FakeKeycloak(val accepted: Set<String>) {
        val tokensIssued = AtomicInteger()
        val deletes = mutableListOf<String>()
        val logouts = mutableListOf<String>()
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
            createContext("/admin/realms/demo/users/") { ex ->
                logouts += ex.requestURI.path
                ex.sendResponseHeaders(204, -1)
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

    given("a cached token that Keycloak rejects after a realm rebuild") {
        val keycloak = FakeKeycloak(accepted = setOf("t2"))
        afterSpec { keycloak.server.stop(0) }
        val client = client(keycloak)

        `when`("an account is removed") {
            client.removeAccount(AccountId(7))

            then("the client fetches a new token and repeats the call once") {
                keycloak.tokensIssued.get() shouldBe 2
                keycloak.deletes shouldBe listOf("t1", "t2")
            }
        }
    }

    given("a Keycloak that rejects the fresh token too") {
        val keycloak = FakeKeycloak(accepted = emptySet())
        afterSpec { keycloak.server.stop(0) }
        val client = client(keycloak)

        `when`("an account is removed") {
            val result = runCatching { client.removeAccount(AccountId(7)) }

            then("the call fails after exactly one retry, instead of fetching tokens endlessly") {
                result.isFailure shouldBe true
                keycloak.tokensIssued.get() shouldBe 2
                keycloak.deletes.size shouldBe 2
            }
        }
    }

    given("an ended invitation (ADR-48)") {
        val keycloak = FakeKeycloak(accepted = setOf("t1"))
        afterSpec { keycloak.server.stop(0) }
        val listener = KeycloakInvitationLogoutListener(client(keycloak))
        val invitation = InvitationId("a".repeat(64))

        `when`("the Personenverzeichnis reports InvitationEnded") {
            listener.onInvitationEnded(InvitationEnded(invitation))

            then("Keycloak logs the invitation user out at once, not only at the next refresh") {
                keycloak.logouts shouldBe listOf("/admin/realms/demo/users/${federatedInvitationUserId(invitation.value)}/logout")
            }
        }
    }
})
