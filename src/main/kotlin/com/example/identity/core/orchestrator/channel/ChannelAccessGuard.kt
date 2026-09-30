package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.kc.PeerAuthAssertion
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.LiveChannel
import com.example.identity.core.orchestrator.session.SessionManagementService
import org.springframework.stereotype.Component
import java.security.MessageDigest
import com.example.identity.core.orchestrator.domain.OrchestratorException

/**
 * "Wer spricht hier, und darf er auf diesen Kanal?" (docs/02-domaenenmodell.md Abschnitt 1). One
 * contract per facade, because the proof differs: a device thumbprint or a verified Keycloak
 * peer-auth assertion. A change to one facade's proof touches one implementation, never the
 * channel resource.
 */
interface ChannelAccessGuard {
    /** Any channel the caller may see, an ended one included, for reading. */
    fun requireChannel(channelSessionId: ChannelSessionId, bindingKeyRef: String): ChannelSession

    /** For anything that moves the channel: an ended one is refused (409), see [LiveChannel]. */
    fun requireLiveChannel(channelSessionId: ChannelSessionId, bindingKeyRef: String): LiveChannel =
        LiveChannel.require(requireChannel(channelSessionId, bindingKeyRef))
}

/**
 * Implementation for the facade-neutral tool endpoints (docs/09-dpop.md #3, docs/05-api.md
 * Abschnitt 3). It accepts what [DpopBindingKeyResolver] resolved: a DPoP thumbprint (App) or a
 * `"kc:"`-prefixed channel binding (Web). `KcChannelService` uses [KcChannelAccessGuard] with the
 * typed [PeerAuthAssertion] instead.
 */
@Component
class DeviceChannelAccessGuard(
    private val sessionManagementService: SessionManagementService
) : ChannelAccessGuard {

    override fun requireChannel(channelSessionId: ChannelSessionId, bindingKeyRef: String): ChannelSession {
        val channel = sessionManagementService.findChannelSessionById(channelSessionId)
            ?: throw OrchestratorException.notFound(Text("Channel session not found"), "channelSessionId=${channelSessionId}")
        val matches = if (bindingKeyRef.startsWith(KC_BINDING_PREFIX)) {
            val presented = bindingKeyRef.removePrefix(KC_BINDING_PREFIX)
            constantTimeEquals(channel.channelBinding, presented)
        } else {
            // Constant-time, though both sides are public thumbprints: cheap insurance should the
            // binding ever carry more.
            constantTimeEquals(channel.bindingKeyRef, bindingKeyRef)
        }
        if (!matches) {
            throw OrchestratorException.bindingMismatch(Text("Caller proof does not match this channel"))
        }
        return channel
    }

    private fun constantTimeEquals(stored: String?, presented: String?): Boolean {
        if (stored == null || presented == null) return false
        return MessageDigest.isEqual(stored.toByteArray(), presented.toByteArray())
    }

    companion object {
        const val KC_BINDING_PREFIX = "kc:"
    }
}

/**
 * WEB implementation (docs/02-domaenenmodell.md Abschnitt 1): the peer-auth assertion must carry
 * this channel's binding. Otherwise a leaked `channelSessionId` plus any validly signed Keycloak
 * assertion would hijack the channel.
 */
@Component
class KcChannelAccessGuard(
    private val sessionManagementService: SessionManagementService
) {

    fun requireChannel(channelSessionId: ChannelSessionId, assertion: PeerAuthAssertion): ChannelSession {
        val channel = sessionManagementService.findChannelSessionById(channelSessionId)
            ?: throw OrchestratorException.notFound(Text("Channel session not found"), "channelSessionId=${channelSessionId}")
        val matches = constantTimeEquals(channel.channelBinding, assertion.channelBinding)
        if (!matches) {
            throw OrchestratorException.bindingMismatch(Text("Keycloak assertion does not match this channel's kc binding"))
        }
        return channel
    }

    private fun constantTimeEquals(stored: String?, presented: String?): Boolean {
        if (stored == null || presented == null) return false
        return MessageDigest.isEqual(stored.toByteArray(), presented.toByteArray())
    }
}
