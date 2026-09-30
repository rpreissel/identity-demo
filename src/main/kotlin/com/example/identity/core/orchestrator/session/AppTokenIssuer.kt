package com.example.identity.core.orchestrator.session

import com.nimbusds.jwt.JWTParser
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The session behind an authenticated APP channel: Keycloak's, or the mock's in the default profile
 * (ADR-43). Every token it hands out moves the channel's expiry to the session window the provider
 * reports, so the channel never outlives the session. Not `@Transactional` itself: a
 * [SessionExpiredException] must not mark the caller's transaction for rollback.
 */
@Service
class AppTokenIssuer(
    private val tokenProvider: TokenProvider,
    private val appTokenSessionService: AppTokenSessionService,
    private val sessionManagementService: SessionManagementService,
    private val clock: Clock
) {

    /** The first call of a login opens the session; later calls return, refresh or re-mint the token. */
    fun tokenFor(channel: ChannelSession, minValiditySeconds: Long = TokenService.DEFAULT_MIN_VALIDITY_SECONDS): TokenPair {
        val pair = tokenProvider.tokenFor(channel, minValiditySeconds)
        channel.expiresAt = pair.refreshExpiresAt
        sessionManagementService.updateChannelSession(channel)
        return pair
    }

    /**
     * A journey interaction on a logged-in channel may renew the session, which moves Keycloak's
     * idle window and the channel's expiry on. It does so only once [RENEWAL_WINDOW_SHARE] of the
     * window since the last token is used up; before that the current window stands.
     */
    fun keepAlive(channel: ChannelSession) {
        val tokenSession = channel.appTokenSessionId?.let(appTokenSessionService::getAppTokenSession) ?: return
        val issuedAt = tokenSession.accessToken?.let(::issuedAtOf)
        val windowEnd = tokenSession.refreshExpiresAt
        if (issuedAt != null && windowEnd != null) {
            val used = Duration.between(issuedAt, clock.instant()).toMillis().toDouble()
            val length = Duration.between(issuedAt, windowEnd).toMillis().toDouble()
            if (length > 0 && used < length * RENEWAL_WINDOW_SHARE) return
        }
        tokenFor(channel, minValiditySeconds = RENEW_ALWAYS_SECONDS)
    }

    private fun issuedAtOf(token: String): Instant? =
        runCatching { JWTParser.parse(token).jwtClaimsSet.issueTime?.toInstant() }.getOrNull()

    companion object {
        /**
         * Renew once a quarter of the window is used: an active user's idle window never drops
         * below three quarters of its length, at a few Keycloak round trips per window instead of
         * one per request. With Keycloak's default of 30 minutes that is one renewal every 7.5.
         */
        const val RENEWAL_WINDOW_SHARE: Double = 0.25

        /** Longer than any access token lives, so no cached token qualifies and the session is renewed. */
        private const val RENEW_ALWAYS_SECONDS: Long = 24 * 60 * 60
    }
}
