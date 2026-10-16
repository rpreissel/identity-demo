package com.example.identity.core.orchestrator.channel

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.CallerProof
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.SessionManagementService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

/**
 * Unit test of [ChannelAccessGuard]: a caller whose proof does not match the channel is refused
 * with 403 (BINDING_MISMATCH), whichever endpoint it calls - reading the channel, logging out or a
 * tool step. An App channel admits its device key, a Web channel Keycloak's assertion for its own id.
 */
class ChannelAccessGuardTest : BehaviorSpec({

    val id = ChannelSessionId(UUID.randomUUID())

    fun guardFor(channel: ChannelSession?) =
        ChannelAccessGuard(mockk<SessionManagementService> { every { findChannelSessionById(id) } returns channel })

    given("an app channel bound to a DPoP key") {
        val channel = ChannelSession(ChannelType.APP, "own-thumbprint", TEST_NOW.plusSeconds(3600), now = TEST_NOW).apply { channelSessionId = id }
        val guard = guardFor(channel)

        `when`("the same key asks for it") {
            val result = guard.requireChannel(id, CallerProof.DeviceKey("own-thumbprint"))

            then("the channel is returned") {
                result shouldBe channel
            }
        }

        `when`("a different DPoP key claims it") {
            val result = runCatching { guard.requireChannel(id, CallerProof.DeviceKey("a-completely-different-binding-key")) }

            then("it is refused as a binding mismatch, 403") {
                val failure = shouldThrow<OrchestratorException> { result.getOrThrow() }
                failure.code shouldBe ErrorCode.BINDING_MISMATCH
                failure.code.httpStatus shouldBe 403
            }
        }

        `when`("Keycloak's assertion for this very id asks") {
            val result = runCatching { guard.requireChannel(id, CallerProof.KeycloakFlow(id)) }

            then("it is refused - an app channel admits only its device key") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.BINDING_MISMATCH
            }
        }
    }

    given("a web channel") {
        val channel = ChannelSession(ChannelType.WEB, null, TEST_NOW.plusSeconds(3600), now = TEST_NOW).apply { channelSessionId = id }
        val guard = guardFor(channel)

        `when`("Keycloak's assertion for its own id asks") {
            val result = guard.requireChannel(id, CallerProof.KeycloakFlow(id))

            then("the channel is returned") {
                result shouldBe channel
            }
        }

        `when`("an assertion for another flow run asks") {
            val result = runCatching { guard.requireChannel(id, CallerProof.KeycloakFlow(ChannelSessionId(UUID.randomUUID()))) }

            then("it is refused as a binding mismatch") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.BINDING_MISMATCH
            }
        }

        `when`("a DPoP key asks for it") {
            val result = runCatching { guard.requireChannel(id, CallerProof.DeviceKey("some-thumbprint")) }

            then("it is refused as a binding mismatch - a web channel has no device key") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.BINDING_MISMATCH
            }
        }
    }

    given("no channel with this id exists") {
        val guard = guardFor(null)

        `when`("a caller asks for it") {
            val result = runCatching { guard.requireChannel(id, CallerProof.DeviceKey("any-thumbprint")) }

            then("it is refused as not found") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.NOT_FOUND
            }
        }
    }
})
