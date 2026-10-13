package com.example.identity.core.orchestrator.keycloak

import com.nimbusds.jose.jwk.JWK
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.Clock

/** The realm's own signing keys (`.../protocol/openid-connect/certs`): what a `logout_token` is signed with. */
@Component
class KeycloakRealmJwkSource(
    @Value("\${keycloak.backchannel-logout.jwks-uri}") jwksUri: String,
    @Value("\${keycloak.backchannel-logout.jwks-cache-ttl-seconds:600}") cacheTtlSeconds: Long,
    clock: Clock,
    // Only under the `keycloak` profile; without it the JVM defaults apply.
    keycloakHttp: KeycloakHttp? = null,
) {
    private val jwks = CachedJwks(jwksUri, cacheTtlSeconds, clock, keycloakHttp, ::LogoutTokenException)

    /** Null for an unknown kid - and for every kid when no realm is configured (blank jwks-uri). */
    fun find(kid: String): JWK? = jwks.find(kid)
}
