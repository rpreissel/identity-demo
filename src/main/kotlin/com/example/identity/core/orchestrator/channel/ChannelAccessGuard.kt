package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.CallerProof
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.LiveChannel
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.session.id
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * "Wer spricht hier, und darf er auf diesen Kanal?" (docs/02-domaenenmodell.md Abschnitt 1). An App
 * channel admits the device whose key opened it; a Web channel admits Keycloak's assertion for its
 * own flow run. Otherwise a leaked `channelSessionId` plus any valid proof would hijack the channel.
 */
@Component
class ChannelAccessGuard(
    private val sessionManagementService: SessionManagementService
) {
    /** Any channel the caller may see, an ended one included, for reading. */
    fun requireChannel(channelSessionId: ChannelSessionId, caller: CallerProof): ChannelSession {
        val channel = sessionManagementService.findChannelSessionById(channelSessionId)
            ?: throw OrchestratorException.notFound(Text("Channel session not found"), "channelSessionId=${channelSessionId}")
        if (!channel.admits(caller)) {
            throw OrchestratorException.bindingMismatch(Text("Caller proof does not match this channel"))
        }
        return channel
    }

    /** For anything that moves the channel: an ended one is refused (409), see [LiveChannel]. */
    fun requireLiveChannel(channelSessionId: ChannelSessionId, caller: CallerProof): LiveChannel =
        LiveChannel.require(requireChannel(channelSessionId, caller))

    private fun ChannelSession.admits(caller: CallerProof): Boolean = when (caller) {
        // Constant-time, though both sides are public thumbprints: cheap insurance should the key ever carry more.
        is CallerProof.DeviceKey -> type == ChannelType.APP && constantTimeEquals(bindingKeyRef, caller.thumbprint)
        is CallerProof.KeycloakFlow -> type == ChannelType.WEB && caller.channelSessionId == channelSessionId
    }

    private fun constantTimeEquals(stored: String?, presented: String): Boolean =
        stored != null && MessageDigest.isEqual(stored.toByteArray(), presented.toByteArray())
}
