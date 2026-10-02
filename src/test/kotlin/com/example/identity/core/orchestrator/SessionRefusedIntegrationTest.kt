package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.session.SessionRefusedException
import com.example.identity.core.orchestrator.session.TokenPair
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.Instant
import java.util.UUID

/**
 * ADR-43: if Keycloak refuses to open the session, the channel does not become AUTHENTICATED. The
 * whole transition rolls back, and the journey can be finished once Keycloak agrees.
 */
class SessionRefusedIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun refuseSessions() {
        every { tokenProvider.tokenFor(any(), any()) } throws SessionRefusedException("refused in test")
    }

    private fun grantSessions() {
        every { tokenProvider.tokenFor(any(), any()) } answers {
            val now = Instant.now()
            TokenPair("token", now.plusSeconds(300), now.plusSeconds(1800))
        }
    }

    /** A login on a fresh channel whose last factor (sms) completes while Keycloak refuses the session. */
    private fun refusedLogin(): Pair<String, Result<Map<String, Any?>>> {
        refuseSessions()
        seedRegisteredAccount()
        val channel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        return channel to runCatching { authenticateViaSms(channel) }
    }

    init {
        given("a login whose session Keycloak refuses") {
            `when`("the last factor completes") {
                val (channel, refused) = refusedLogin()
                val afterRefusal = get("/orchestrator/api/v1/channels/$channel")

                then("the request is refused with 409") {
                    shouldThrow<HttpClientErrorException> { refused.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
                then("the channel stays unauthenticated, and the rolled-back journey still offers the login") {
                    afterRefusal.channel()["state"] shouldNotBe "AUTHENTICATED"
                    afterRefusal.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
                }
                then("the journey keeps running") {
                    jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM orchestrator.auth_journey WHERE channel_session_id = ? AND lifecycle = 'STARTED'",
                        Int::class.java, UUID.fromString(channel)
                    ) shouldBe 1
                }
            }

            `when`("Keycloak agrees afterwards and the user picks the method again") {
                val (channel, _) = refusedLogin()
                grantSessions()
                delete("/orchestrator/api/v1/channels/$channel/journey")

                val authenticated = authenticateViaSms(channel)
                val state = get("/orchestrator/api/v1/channels/$channel").channel()["state"]

                then("the second attempt succeeds") {
                    authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    state shouldBe "AUTHENTICATED"
                }
            }
        }
    }
}
