package com.example.identity.core.orchestrator.session

/**
 * The profile-switchable half of `GET /channels/{id}/token`, APP channels only. [MockTokenProvider]
 * serves the default profile, [KeycloakTokenProvider] the `keycloak` profile. `ChannelService` depends on
 * this interface only.
 */
interface TokenProvider {
    fun tokenFor(channel: ChannelSession, minValiditySeconds: Long = TokenService.DEFAULT_MIN_VALIDITY_SECONDS): TokenPair
}
