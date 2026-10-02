package com.example.identity.core.orchestrator.channel

import com.example.identity.TEST_NOW
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
 * Unit test of [DeviceChannelAccessGuard], the guard of the facade-neutral endpoints: a caller whose
 * proof does not match the channel is refused with 403 (BINDING_MISMATCH), whichever endpoint it
 * calls - reading the channel, logging out or a tool step.
 */
class DeviceChannelAccessGuardTest : BehaviorSpec({

    val id = ChannelSessionId(UUID.randomUUID())

    fun guardFor(channel: ChannelSession?) =
        DeviceChannelAccessGuard(mockk<SessionManagementService> { every { findChannelSessionById(id) } returns channel })

    given("an app channel bound to a DPoP key") {
        val channel = ChannelSession(ChannelType.APP, "own-thumbprint", TEST_NOW.plusSeconds(3600), now = TEST_NOW)
        val guard = guardFor(channel)

        `when`("the same key asks for it") {
            val result = guard.requireChannel(id, "own-thumbprint")

            then("the channel is returned") {
                result shouldBe channel
            }
        }

        `when`("a different DPoP key claims it") {
            val result = runCatching { guard.requireChannel(id, "a-completely-different-binding-key") }

            then("it is refused as a binding mismatch, 403") {
                val failure = shouldThrow<OrchestratorException> { result.getOrThrow() }
                failure.code shouldBe ErrorCode.BINDING_MISMATCH
                failure.code.httpStatus shouldBe 403
            }
        }
    }

    given("a web channel bound to a Keycloak channel binding") {
        val channel = ChannelSession(ChannelType.WEB, null, TEST_NOW.plusSeconds(3600), now = TEST_NOW).apply {
            channelBinding = "binding-1"
        }
        val guard = guardFor(channel)

        `when`("Keycloak's binding for it asks") {
            val result = guard.requireChannel(id, "${DeviceChannelAccessGuard.KC_BINDING_PREFIX}binding-1")

            then("the channel is returned") {
                result shouldBe channel
            }
        }

        `when`("another Keycloak binding asks") {
            val result = runCatching { guard.requireChannel(id, "${DeviceChannelAccessGuard.KC_BINDING_PREFIX}someone-elses-binding") }

            then("it is refused as a binding mismatch") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.BINDING_MISMATCH
            }
        }

        `when`("a DPoP key asks for it") {
            val result = runCatching { guard.requireChannel(id, "some-thumbprint") }

            then("it is refused as a binding mismatch - a web channel has no device key") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.BINDING_MISMATCH
            }
        }
    }

    given("no channel with this id exists") {
        val guard = guardFor(null)

        `when`("a caller asks for it") {
            val result = runCatching { guard.requireChannel(id, "any-thumbprint") }

            then("it is refused as not found") {
                shouldThrow<OrchestratorException> { result.getOrThrow() }.code shouldBe ErrorCode.NOT_FOUND
            }
        }
    }
})
