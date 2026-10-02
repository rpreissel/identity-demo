package com.example.identity.core.orchestrator.api.v1

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.channel.KcChannelAccessGuard
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.kc.PeerAuthAssertion
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.SessionManagementService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

/** Pure unit test of [KcChannelAccessGuard] - the kc binding mismatch is the whole point of this class. */
class KcChannelAccessGuardTest : BehaviorSpec({

    fun assertion(channelBinding: String) =
        PeerAuthAssertion(jti = UUID.randomUUID().toString(), issuedAt = TEST_NOW, channelBinding = channelBinding, subject = null)

    given("a channel bound on channelBinding") {
        val id = ChannelSessionId(UUID.randomUUID())
        val channel = ChannelSession(ChannelType.WEB, null, TEST_NOW.plusSeconds(3600), now = TEST_NOW).apply {
            channelBinding = "binding-1"
        }
        val guard = KcChannelAccessGuard(mockk<SessionManagementService> { every { findChannelSessionById(id) } returns channel })

        `when`("the assertion claims the matching channelBinding") {
            val result = guard.requireChannel(id, assertion(channelBinding = "binding-1"))

            then("the channel is returned") {
                result shouldBe channel
            }
        }

        `when`("the assertion claims a different channelBinding") {
            val result = runCatching { guard.requireChannel(id, assertion(channelBinding = "someone-elses-binding")) }

            then("it is rejected as a binding mismatch") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.BINDING_MISMATCH
            }
        }
    }

    given("no channel with this id exists") {
        val id = ChannelSessionId(UUID.randomUUID())
        val guard = KcChannelAccessGuard(mockk<SessionManagementService> { every { findChannelSessionById(id) } returns null })

        `when`("an assertion asks for it") {
            val result = runCatching { guard.requireChannel(id, assertion(channelBinding = "x")) }

            then("it is rejected as not found") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.NOT_FOUND
            }
        }
    }
})
