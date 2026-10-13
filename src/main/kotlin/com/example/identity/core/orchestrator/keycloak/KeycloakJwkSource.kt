package com.example.identity.core.orchestrator.keycloak

import com.nimbusds.jose.jwk.JWK
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock

/** The extension's peer-auth keys (ADR-7): what Keycloak signs its facade requests with. */
@Component
class KeycloakJwkSource(
    @Value("\${keycloak.peer-auth.jwks-uri}") jwksUri: String,
    @Value("\${keycloak.peer-auth.jwks-cache-ttl-seconds:600}") cacheTtlSeconds: Long,
    clock: Clock,
    // Only under the `keycloak` profile; without it the JVM defaults apply.
    keycloakHttp: KeycloakHttp? = null,
) {
    private val jwks = CachedJwks(jwksUri, cacheTtlSeconds, clock, keycloakHttp) { message, cause ->
        if (cause == null) PeerAuthValidationException(message) else PeerAuthValidationException(message, cause)
    }

    /** Null for an unknown kid - and for every kid when no issuer is configured (blank jwks-uri). */
    fun find(kid: String): JWK? = jwks.find(kid)
}
