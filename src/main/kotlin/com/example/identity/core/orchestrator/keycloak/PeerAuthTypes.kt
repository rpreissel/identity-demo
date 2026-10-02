package com.example.identity.core.orchestrator.keycloak

import java.time.Instant

/**
 * A verified peer-auth assertion (ADR-7, docs/02-domaenenmodell.md Abschnitt 1): "this is Keycloak,
 * acting for this channel", never who the end user is. [channelBinding] is the flow run's own
 * `channelSessionId`, so two tabs on the same SSO session never share a binding. Keycloak's durable
 * user session id travels separately, only where RestoreData needs it.
 */
data class PeerAuthAssertion(
    val jti: String,
    val issuedAt: Instant,
    val channelBinding: String,
    val subject: String?
)

class PeerAuthValidationException : RuntimeException {
    constructor(message: String) : super(message)
    constructor(message: String, cause: Throwable) : super(message, cause)
}
