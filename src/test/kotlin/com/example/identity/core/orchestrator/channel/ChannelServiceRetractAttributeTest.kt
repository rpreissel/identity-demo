package com.example.identity.core.orchestrator.channel

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.journey.JourneyService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.LiveChannel
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

/**
 * Unit test of [ChannelService.retractAttribute]: the holder may give up only a local anchor whose
 * rule allows it (`AnchorRule.retractableByHolder`), today the confirmed address. An identity anchor,
 * or a name that is no attribute, is refused with 409 before any journey starts.
 */
class ChannelServiceRetractAttributeTest : BehaviorSpec({

    given("an authenticated App channel of an identified account") {
        listOf("person_id", "member_number", "restricted_id", "nect_restricted_id", "versnr").forEach { attribute ->
            `when`("the holder tries to withdraw $attribute") {
                val fixture = RetractAttributeFixture()
                val result = runCatching { fixture.service.retractAttribute(fixture.channelSessionId, "own-key", attribute) }

                then("it is refused with 409, and no journey starts") {
                    shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.INVALID_STATE_TRANSITION
                    verify(exactly = 0) { fixture.journeyService.start(any(), any(), any()) }
                }
            }
        }

        `when`("the holder withdraws the confirmed address") {
            val fixture = RetractAttributeFixture()
            fixture.service.retractAttribute(fixture.channelSessionId, "own-key", "email")

            then("the manage journey starts with that wish") {
                verify {
                    fixture.journeyService.start(any(), AuthIntent.MANAGE_AUTH_METHODS, ManageAuthMethodsState.RetractAttributeRequested(AttributeType.EMAIL))
                }
            }
        }
    }
})

/** [ChannelService] over relaxed mocks, with one authenticated App channel of account 7. */
private class RetractAttributeFixture {
    val channelSessionId = ChannelSessionId(UUID.randomUUID())
    private val channel = ChannelSession(ChannelType.APP, "own-key", TEST_NOW.plusSeconds(3600), now = TEST_NOW).apply {
        state = ChannelState.AUTHENTICATED
        subject = Subject.Account(AccountId(7L))
    }
    val journeyService = mockk<JourneyService>(relaxed = true)
    val service = ChannelService(
        sessionManagementService = mockk(relaxed = true),
        accountService = mockk(relaxed = true),
        appTokenSessionService = mockk(relaxed = true),
        sessionEvidenceService = mockk(relaxed = true),
        authPolicy = mockk(relaxed = true),
        channelAccessGuard = mockk { every { requireLiveChannel(channelSessionId, any()) } returns LiveChannel.require(channel) },
        journeyService = journeyService,
        tokenService = mockk(relaxed = true),
        appTokenIssuer = mockk(relaxed = true),
        channelCreationRateLimitService = mockk(relaxed = true),
        journeyTraceService = mockk(relaxed = true),
        toolRegistry = mockk(relaxed = true),
        responseAssembler = mockk(relaxed = true),
    )
}
