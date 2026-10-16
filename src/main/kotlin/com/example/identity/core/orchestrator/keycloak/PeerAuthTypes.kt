package com.example.identity.core.orchestrator.keycloak

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.CallerProof
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import java.time.Instant
import java.util.UUID

/**
 * A verified peer-auth assertion (ADR-7, docs/02-domaenenmodell.md Abschnitt 1): "this is Keycloak,
 * acting for this channel", never who the end user is. [channelBinding] is the flow run's own
 * `channelSessionId`, so two tabs on the same SSO session never share a binding. Keycloak's durable
 * user session id travels separately, only where the session's evidence needs it (ADR-59).
 */
data class PeerAuthAssertion(
    val jti: String,
    val issuedAt: Instant,
    val channelBinding: String,
    val subject: String?
)

/**
 * This assertion as the caller of the channel it is bound to. A binding that names no channel (the
 * lookup endpoints bind their assertions to other things) matches no channel: 403, as any other
 * mismatch.
 */
fun PeerAuthAssertion.asChannelCaller(): CallerProof.KeycloakFlow {
    val id = runCatching { UUID.fromString(channelBinding) }.getOrNull()
        ?: throw OrchestratorException.bindingMismatch(Text("Keycloak assertion does not match this channel's Keycloak binding"))
    return CallerProof.KeycloakFlow(ChannelSessionId(id))
}

class PeerAuthValidationException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
