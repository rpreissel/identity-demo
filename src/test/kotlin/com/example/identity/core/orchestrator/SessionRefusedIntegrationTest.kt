package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.example.identity.core.orchestrator.session.SessionRefusedException
import com.example.identity.core.orchestrator.session.TokenPair
import com.example.identity.core.orchestrator.session.TokenProvider
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.Instant
import java.util.UUID

/**
 * ADR-43: if Keycloak refuses to open the session, the channel does not become AUTHENTICATED. The
 * whole transition rolls back, and the journey can be finished once Keycloak agrees.
 */
class SessionRefusedIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @MockkBean
    private lateinit var tokenProvider: TokenProvider

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a login whose last factor completes while Keycloak refuses the session") {
            then("the channel stays unauthenticated, the journey keeps running, and a second attempt succeeds") {
                every { tokenProvider.tokenFor(any(), any()) } throws SessionRefusedException("refused in test")
                seedRegisteredAccount()
                val channel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

                val refused = assertThrows<HttpClientErrorException> { authenticateViaSms(channel) }
                refused.statusCode shouldBe HttpStatus.CONFLICT

                val afterRefusal = get("/orchestrator/api/v1/channels/$channel")
                afterRefusal.channel()["state"] shouldNotBe "AUTHENTICATED"
                afterRefusal["next"] shouldNotBe null
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orchestrator.auth_journey WHERE channel_session_id = ? AND lifecycle = 'STARTED'",
                    Int::class.java, UUID.fromString(channel)
                ) shouldBe 1

                every { tokenProvider.tokenFor(any(), any()) } answers {
                    val now = Instant.now()
                    TokenPair("token", now.plusSeconds(300), now.plusSeconds(1800))
                }
                // The refused request rolled back; the user picks the method again.
                delete("/orchestrator/api/v1/channels/$channel/journey")
                val authenticated = authenticateViaSms(channel)
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                get("/orchestrator/api/v1/channels/$channel").channel()["state"] shouldBe "AUTHENTICATED"
            }
        }
    }
}
