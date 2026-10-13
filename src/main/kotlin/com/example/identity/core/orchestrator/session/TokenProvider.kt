package com.example.identity.core.orchestrator.session

/**
 * The token half of `GET /channels/{id}/token`, APP channels only: [KeycloakTokenProvider], in tests
 * the stand-in `MockTokenProvider` (src/testFixtures, ADR-60). `ChannelService` depends on
 * this interface only.
 */
interface TokenProvider {
    fun tokenFor(channel: ChannelSession, minValiditySeconds: Long = TokenService.DEFAULT_MIN_VALIDITY_SECONDS): TokenPair
}
