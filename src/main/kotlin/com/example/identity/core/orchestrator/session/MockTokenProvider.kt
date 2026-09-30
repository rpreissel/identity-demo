package com.example.identity.core.orchestrator.session

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

/** Default-profile [TokenProvider]: the mock [TokenService]. */
@Service
@Profile("!keycloak")
class MockTokenProvider(
    private val tokenService: TokenService
) : TokenProvider {
    override fun tokenFor(channel: ChannelSession, minValiditySeconds: Long): TokenPair =
        tokenService.tokenFor(channel.appTokenSessionId!!, minValiditySeconds)
}
