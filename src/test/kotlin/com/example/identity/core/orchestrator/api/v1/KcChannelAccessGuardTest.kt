package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.channel.KcChannelAccessGuard
import com.example.identity.core.orchestrator.kc.PeerAuthAssertion
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.SessionManagementService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID
import com.example.identity.core.orchestrator.domain.OrchestratorException

/** Pure unit test of [KcChannelAccessGuard] - the kc binding mismatch is the whole point of this class. */
class KcChannelAccessGuardTest : BehaviorSpec({

    fun channel(channelBinding: String? = null) =
        ChannelSession(ChannelType.WEB, null, Instant.now().plusSeconds(3600), now = Instant.now()).apply {
            this.channelBinding = channelBinding
        }

    fun assertion(channelBinding: String) =
        PeerAuthAssertion(
            jti = UUID.randomUUID().toString(),
            issuedAt = Instant.now(),
            channelBinding = channelBinding,
            subject = null
        )

    fun guardFor(channel: ChannelSession, id: ChannelSessionId): KcChannelAccessGuard {
        val sessionManagementService = mockk<SessionManagementService> {
            every { findChannelSessionById(id) } returns channel
        }
        return KcChannelAccessGuard(sessionManagementService)
    }

    given("a channel bound on channelBinding") {
        val id = ChannelSessionId(UUID.randomUUID())
        val channel = channel(channelBinding = "binding-1")

        `when`("the assertion claims the matching channelBinding") {
            then("the channel is returned") {
                guardFor(channel, id).requireChannel(id, assertion(channelBinding = "binding-1")) shouldBe channel
            }
        }
        `when`("the assertion claims a different channelBinding") {
            then("it is rejected as a binding mismatch") {
                shouldThrow<OrchestratorException> {
                    guardFor(channel, id).requireChannel(id, assertion(channelBinding = "someone-elses-binding"))
                }
            }
        }
    }

    given("no channel with this id exists") {
        then("it is rejected as not found") {
            val id = ChannelSessionId(UUID.randomUUID())
            val sessionManagementService = mockk<SessionManagementService> {
                every { findChannelSessionById(id) } returns null
            }
            shouldThrow<OrchestratorException> {
                KcChannelAccessGuard(sessionManagementService).requireChannel(id, assertion(channelBinding = "x"))
            }
        }
    }
})
