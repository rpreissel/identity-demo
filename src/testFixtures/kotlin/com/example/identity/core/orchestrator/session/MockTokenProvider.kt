package com.example.identity.core.orchestrator.session

import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import org.springframework.context.annotation.Profile
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Stands in for Keycloak where there is none: the backend tests and the browser tests
 * (`bootRunE2e`). The AccessToken is an unsecured JWT (alg=none), so the frontend can show its claims
 * without a key. The RefreshToken is a server-side secret; only its expiry is returned. Never part of
 * the delivered jar (docs/13-ausfuehren.md Abschnitt 7).
 */
@Service
@Profile("!keycloak")
// SessionExpiredException is an answer, not a failure: the caller ends the channel in the same
// transaction, which must still commit (ChannelService.getToken).
@Transactional(noRollbackFor = [SessionExpiredException::class])
class MockTokenProvider(
    private val appTokenSessionRepository: AppTokenSessionRepository,
    private val tokenService: TokenService,
    private val vault: AppTokenVault,
    private val clock: Clock
) : TokenProvider {

    override fun tokenFor(channel: ChannelSession, minValiditySeconds: Long): TokenPair =
        tokenFor(channel.appTokenSessionId!!, minValiditySeconds)

    /**
     * First issuance and refresh alike. A token with at least [minValiditySeconds] left comes back
     * unchanged; otherwise a new one is minted, which also slides the session window
     * ([REFRESH_TTL] idle timeout). Like Keycloak, only the very first call opens a session
     * ([AppTokenSession.keycloakSessionId]); once its window has lapsed the login ends with
     * [SessionExpiredException], also after a step-up (ADR-43).
     */
    fun tokenFor(appTokenSessionId: UUID, minValiditySeconds: Long = TokenService.DEFAULT_MIN_VALIDITY_SECONDS): TokenPair {
        val appTokenSession = checkNotNull(appTokenSessionRepository.findByIdOrNull(appTokenSessionId)) {
            "AppTokenSession not found: $appTokenSessionId"
        }
        val now = clock.instant()

        val currentExpiry = appTokenSession.accessExpiresAt
        val tokens = vault.forSession(appTokenSession)
        val cached = tokens.accessToken
        if (cached != null && currentExpiry != null && currentExpiry.isAfter(now.plusSeconds(minValiditySeconds))) {
            return TokenPair(cached, currentExpiry, appTokenSession.refreshExpiresAt!!)
        }

        if (appTokenSession.keycloakSessionId == null) {
            appTokenSession.keycloakSessionId = "$MOCK_SESSION_PREFIX${UUID.randomUUID()}"
        } else if (appTokenSession.refreshExpiresAt?.isAfter(now) != true) {
            throw SessionExpiredException("Session window of AppTokenSession $appTokenSessionId has lapsed")
        }
        if (appTokenSession.sealedRefreshToken == null) tokens.refreshToken = "mockrt_${UUID.randomUUID()}"
        // Sliding: every refresh moves the window on, so it lapses only after REFRESH_TTL of idleness.
        appTokenSession.refreshExpiresAt = now.plus(REFRESH_TTL)

        val accessExpiresAt = now.plus(ACCESS_TTL)
        val accessToken = mintAccessToken(appTokenSessionId, now, accessExpiresAt)
        tokens.accessToken = accessToken
        appTokenSession.accessExpiresAt = accessExpiresAt
        appTokenSessionRepository.save(appTokenSession)

        return TokenPair(accessToken, accessExpiresAt, appTokenSession.refreshExpiresAt!!)
    }

    /** `sub`, `acr` and `amr` as [TokenService.idClaims] has them, the way Keycloak's mapper writes them. */
    private fun mintAccessToken(appTokenSessionId: UUID, iat: Instant, exp: Instant): String {
        val idClaims = tokenService.idClaims(appTokenSessionId)
        val claims = JWTClaimsSet.Builder()
            .subject(idClaims["sub"] as String?)
            .issuer(MOCK_ISSUER)
            .audience(MOCK_AUDIENCE)
            .claim("acr", idClaims["acr"])
            .claim("amr", idClaims["amr"])
            .issueTime(Date.from(iat))
            .expirationTime(Date.from(exp))
            .jwtID(UUID.randomUUID().toString())
            .build()
        return PlainJWT(claims).serialize()
    }

    companion object {
        private val ACCESS_TTL: Duration = Duration.ofMinutes(5)
        private val REFRESH_TTL: Duration = Duration.ofMinutes(30)
        private const val MOCK_ISSUER = "mock-keycloak"
        const val MOCK_SESSION_PREFIX = "mock-session-"
        private const val MOCK_AUDIENCE = "identity-demo-orchestrator"
    }
}
