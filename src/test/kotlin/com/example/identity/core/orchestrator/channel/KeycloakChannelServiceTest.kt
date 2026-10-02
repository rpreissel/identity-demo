package com.example.identity.core.orchestrator.channel

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.keycloak.PeerAuthAssertion
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.SessionManagementService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.util.UUID

/**
 * Unit test of [KeycloakChannelService]'s own rules, with every collaborator mocked: which entry intents
 * the Web channel accepts, and that a channel never outlives the Keycloak session it ends in (ADR-43).
 */
class KeycloakChannelServiceTest : BehaviorSpec({

    given("a fresh Web channel") {
        listOf(null to AuthIntent.WEB_SELECT_METHOD, "register" to AuthIntent.REGISTER).forEach { (intent, expected) ->
            `when`("it is opened with intent=$intent") {
                val fixture = KeycloakChannelFixture()
                fixture.service.upsertChannel(fixture.channelSessionId, fixture.assertion, subject = null, targetAcr = null, intent = intent)

                then("its entry journey is ${expected.name}") {
                    fixture.entryIntent.captured shouldBe expected
                }
            }
        }

        listOf("lookup_login", "fast_access", "bogus").forEach { intent ->
            `when`("it is opened with intent=$intent") {
                val fixture = KeycloakChannelFixture()
                val result = runCatching {
                    fixture.service.upsertChannel(fixture.channelSessionId, fixture.assertion, subject = null, targetAcr = null, intent = intent)
                }

                then("it is rejected up front with 409, never silently mapped to web_select_method") {
                    shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.INVALID_STATE_TRANSITION
                    verify(exactly = 0) { fixture.sessionManagementService.createWebChannelSession(any(), any(), any(), any(), any(), any()) }
                }
            }
        }
    }

    given("a Web channel that lives 30 more minutes") {
        `when`("its flow run ends in a Keycloak session that ends in 2 minutes") {
            val fixture = KeycloakChannelFixture()
            val sessionEnd = TEST_NOW.plus(Duration.ofMinutes(2))
            fixture.service.restoreData(fixture.channelSessionId, fixture.assertion, "kc-session-1", sessionExpiresAt = sessionEnd)

            then("the channel's expiry is capped at the session's end, and the durable session id recorded") {
                fixture.existing.expiresAt shouldBe sessionEnd
                fixture.existing.durableKeycloakSessionId shouldBe "kc-session-1"
                verify { fixture.sessionManagementService.updateChannelSession(fixture.existing) }
            }
        }

        `when`("its flow run ends in a Keycloak session that outlasts it") {
            val fixture = KeycloakChannelFixture()
            val before = fixture.existing.expiresAt
            fixture.service.restoreData(fixture.channelSessionId, fixture.assertion, "kc-session-1", sessionExpiresAt = TEST_NOW.plus(Duration.ofHours(10)))

            then("the channel keeps its shorter lifetime") {
                fixture.existing.expiresAt shouldBe before
            }
        }
    }
})

/**
 * The service over relaxed mocks. Upserting [channelSessionId] creates a fresh channel; reading it
 * back for [restoreData][KeycloakChannelService.restoreData] finds [existing], which lives 30 more minutes.
 */
private class KeycloakChannelFixture {
    val channelSessionId = ChannelSessionId(UUID.randomUUID())
    val assertion = PeerAuthAssertion(
        jti = UUID.randomUUID().toString(), issuedAt = TEST_NOW, channelBinding = channelSessionId.toString(), subject = null
    )
    val existing = ChannelSession(ChannelType.WEB, null, TEST_NOW.plus(Duration.ofMinutes(30)), now = TEST_NOW).apply {
        channelBinding = channelSessionId.toString()
    }
    val entryIntent = slot<AuthIntent>()
    val sessionManagementService = mockk<SessionManagementService>(relaxed = true) {
        every { findChannelSessionById(channelSessionId) } returns null
        every { createWebChannelSession(channelSessionId, any(), any(), any(), any(), capture(entryIntent)) } returns existing
    }
    private val keycloakChannelAccessGuard = mockk<KeycloakChannelAccessGuard> {
        every { requireChannel(channelSessionId, assertion) } returns existing
    }
    val service = KeycloakChannelService(
        sessionManagementService = sessionManagementService,
        keycloakChannelAccessGuard = keycloakChannelAccessGuard,
        channelService = mockk(relaxed = true),
        journeyService = mockk(relaxed = true),
        accountService = mockk(relaxed = true),
        sessionEvidenceService = mockk(relaxed = true),
        restoreDataCodec = mockk(relaxed = true),
        nativeAuthenticatorRegistry = mockk(relaxed = true),
        channelSessionRepository = mockk(relaxed = true),
        signInLog = mockk(relaxed = true),
        appTokenSessionService = mockk(relaxed = true),
    )
}
