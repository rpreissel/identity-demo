package com.example.identity.core.orchestrator.channel

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.session.ChannelSession
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

/**
 * Token and ID claims exist only for an authenticated APP channel. A WEB channel never has
 * an auth context - it must be refused as the wrong kind of channel (409), not fail on the
 * missing context (500).
 */
class ChannelServiceTokenAccessTest : BehaviorSpec({

    given("an authenticated WEB channel") {
        val channelSessionId = ChannelSessionId(UUID.randomUUID())
        val guard = mockk<ChannelAccessGuard> {
            every { requireChannel(channelSessionId, any()) } returns
                ChannelSession(channel = ChannelType.WEB, now = TEST_NOW).apply { state = ChannelState.AUTHENTICATED }
        }
        val service = ChannelService(
            sessionManagementService = mockk(relaxed = true),
            accountService = mockk(relaxed = true),
            appTokenSessionService = mockk(relaxed = true),
            sessionEvidenceService = mockk(relaxed = true),
            authPolicy = mockk(relaxed = true),
            channelAccessGuard = guard,
            journeyService = mockk(relaxed = true),
            tokenService = mockk(relaxed = true),
            appTokenIssuer = mockk(relaxed = true),
            channelCreationRateLimitService = mockk(relaxed = true),
            journeyTraceService = mockk(relaxed = true),
            toolRegistry = mockk(relaxed = true),
            responseAssembler = mockk(relaxed = true),
        )

        `when`("its AccessToken is requested") {
            val result = runCatching { service.getToken(channelSessionId, "kc", 30) }

            then("it is refused with 409") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.INVALID_STATE_TRANSITION
            }
        }

        `when`("its ID claims are requested") {
            val result = runCatching { service.getIdClaims(channelSessionId, "kc") }

            then("they are refused with 409") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.INVALID_STATE_TRANSITION
            }
        }
    }
})
