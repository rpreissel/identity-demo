package com.example.identity.core.orchestrator.session

import org.springframework.web.client.HttpClientErrorException
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.keycloak.AccountTokenResponse
import com.example.identity.core.orchestrator.keycloak.KeycloakAdminClient
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.nimbusds.jwt.SignedJWT
import org.springframework.context.annotation.Profile
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * `keycloak`-profile [TokenProvider]: fetches a real Keycloak-signed access token via the
 * `urn:identity-demo:account-token` grant (ADR-9). The App never talks to Keycloak itself; WEB
 * channels hold their own tokens. A still valid token is returned; a RefreshToken is used while it
 * lives; otherwise the current acr/amr go through the grant, into the login's one session. Any
 * evidence change clears the cached tokens ([SessionEvidenceService]), so a surviving RefreshToken
 * always matches the current acr/amr.
 */
@Service
@Profile("keycloak")
class KeycloakTokenProvider(
    private val appTokenSessionRepository: AppTokenSessionRepository,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val sessionEvidenceService: SessionEvidenceService,
    private val authPolicy: AuthPolicy,
    private val accountService: AccountService,
    private val vault: AppTokenVault,
    private val clock: Clock
) : TokenProvider {

    override fun tokenFor(channel: ChannelSession, minValiditySeconds: Long): TokenPair {
        val appTokenSessionId = channel.appTokenSessionId!!
        val appTokenSession = checkNotNull(appTokenSessionRepository.findByIdOrNull(appTokenSessionId)) {
            "AppTokenSession not found: $appTokenSessionId"
        }
        val accountId = checkNotNull(appTokenSession.accountId) { "AppTokenSession $appTokenSessionId has no accountId" }
        val now = clock.instant()

        val currentExpiry = appTokenSession.accessExpiresAt
        val cached = vault.accessTokenOf(appTokenSession)
        if (cached != null && currentExpiry != null && currentExpiry.isAfter(now.plusSeconds(minValiditySeconds))) {
            return TokenPair(cached, currentExpiry, appTokenSession.refreshExpiresAt ?: currentExpiry)
        }

        // Only the first token of this login opens a Keycloak session (ADR-43). After that, Keycloak's
        // own session decides (SSO idle and max): a lapsed window or a refused refresh or
        // continuation ends the login instead of opening a second session behind its back.
        val sessionId = appTokenSession.keycloakSessionId
        val refreshToken = vault.refreshTokenOf(appTokenSession)
        if (sessionId != null && appTokenSession.refreshExpiresAt?.isAfter(now) != true) {
            throw SessionExpiredException("Keycloak session window of AppTokenSession $appTokenSessionId has lapsed")
        }
        val response = try {
            when {
                sessionId == null -> requestAccountToken(appTokenSession, sessionId = null)
                // Evidence changed (a step-up): the new acr/amr go into the same session.
                refreshToken == null -> requestAccountToken(appTokenSession, sessionId)
                else -> keycloakAdminClient.refreshAccountToken(refreshToken)
            }
        } catch (e: HttpClientErrorException) {
            if (sessionId == null) {
                throw SessionRefusedException("Keycloak refused to open a session for AppTokenSession $appTokenSessionId: ${e.statusCode}")
            }
            throw SessionExpiredException("Keycloak refused to continue session $sessionId of AppTokenSession $appTokenSessionId: ${e.statusCode}")
        }

        val accessExpiresAt = now.plusSeconds(response.expiresInSeconds)
        vault.storeAccessToken(appTokenSession, response.accessToken)
        appTokenSession.accessExpiresAt = accessExpiresAt
        // The Keycloak session of this login (`sid`): every later grant continues exactly this one,
        // and an App logout ends it.
        appTokenSession.keycloakSessionId = sessionId
            ?: checkNotNull(sidClaimOf(response.accessToken)) { "Keycloak token for AppTokenSession $appTokenSessionId carries no sid" }
        if (response.refreshToken != null) {
            vault.storeRefreshToken(appTokenSession, response.refreshToken)
            appTokenSession.refreshExpiresAt = response.refreshExpiresInSeconds?.let { now.plusSeconds(it) }
        }
        appTokenSessionRepository.save(appTokenSession)

        return TokenPair(response.accessToken, accessExpiresAt, appTokenSession.refreshExpiresAt ?: accessExpiresAt)
    }

    /**
     * The same acr/amr [TokenService.mintAccessToken] puts into the mock JWT; the grant copies them
     * onto the Keycloak session. Plain parameters: only the orchestrator's client may call it (ADR-9).
     * Without [sessionId] the grant opens the login's session, with it the grant continues it (ADR-43).
     */
    private fun requestAccountToken(appTokenSession: AppTokenSession, sessionId: String?): AccountTokenResponse {
        val accountId = checkNotNull(appTokenSession.accountId)
        val account = accountService.findAccount(accountId)
        val evidence = appTokenSession.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) }?.toCoreEvidence()
        return keycloakAdminClient.requestAccountToken(
            accountId,
            acr = evidence?.let { authPolicy.resolveAcr(it, account) }?.value,
            amr = evidence?.amr?.map { it.value }.orEmpty(),
            sessionId = sessionId,
        )
    }

    /** Parsed without verification: the token comes straight from Keycloak's token endpoint. */
    private fun sidClaimOf(accessToken: String): String? =
        runCatching { SignedJWT.parse(accessToken).jwtClaimsSet.getStringClaim("sid") }.getOrNull()

}
