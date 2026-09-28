package com.example.identity.core.orchestrator.session

import org.springframework.web.client.HttpClientErrorException
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.kc.AccountTokenResponse
import com.example.identity.core.orchestrator.kc.KeycloakAdminClient
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.nimbusds.jwt.SignedJWT
import org.springframework.context.annotation.Profile
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * `keycloak`-profile [TokenProvider]: fetches a real Keycloak-signed access token via the
 * `urn:identity-demo:account-token` grant (ADR-9). The App never talks to Keycloak itself; KEYCLOAK
 * channels hold their own tokens. A still valid token is returned; a RefreshToken is used while it
 * lives; otherwise the current acr/amr go through the grant, into the login's one session. Any
 * evidence change clears the cached tokens ([AuthEvidenceService]), so a surviving RefreshToken
 * always matches the current acr/amr.
 */
@Service
@Profile("keycloak")
class KcTokenProvider(
    private val authContextRepository: AuthContextRepository,
    private val keycloakAdminClient: KeycloakAdminClient,
    private val authEvidenceService: AuthEvidenceService,
    private val authPolicy: AuthPolicy,
    private val accountService: AccountService,
    private val clock: Clock
) : TokenProvider {

    override fun tokenFor(channel: ChannelSession, minValiditySeconds: Long): TokenPair {
        val authContextId = channel.authContextId!!
        val authContext = checkNotNull(authContextRepository.findByIdOrNull(authContextId)) {
            "AuthContext not found: $authContextId"
        }
        val accountId = checkNotNull(authContext.accountId) { "AuthContext $authContextId has no accountId" }
        val now = clock.instant()

        val currentExpiry = authContext.accessExpiresAt
        if (authContext.accessToken != null && currentExpiry != null &&
            currentExpiry.isAfter(now.plusSeconds(minValiditySeconds))
        ) {
            return TokenPair(authContext.accessToken!!, currentExpiry, authContext.refreshExpiresAt ?: currentExpiry)
        }

        // Only the first token of this login opens a Keycloak session (ADR-43). After that, Keycloak's
        // own session decides (SSO idle and max): a lapsed window or a refused refresh or
        // continuation ends the login instead of opening a second session behind its back.
        val sessionId = authContext.keycloakSessionId
        val refreshToken = authContext.refreshToken
        if (sessionId != null && authContext.refreshExpiresAt?.isAfter(now) != true) {
            throw SessionExpiredException("Keycloak session window of AuthContext $authContextId has lapsed")
        }
        val response = try {
            when {
                sessionId == null -> requestAccountToken(authContext, sessionId = null)
                // Evidence changed (a step-up): the new acr/amr go into the same session.
                refreshToken == null -> requestAccountToken(authContext, sessionId)
                else -> keycloakAdminClient.refreshAccountToken(refreshToken)
            }
        } catch (e: HttpClientErrorException) {
            if (sessionId == null) {
                throw SessionRefusedException("Keycloak refused to open a session for AuthContext $authContextId: ${e.statusCode}")
            }
            throw SessionExpiredException("Keycloak refused to continue session $sessionId of AuthContext $authContextId: ${e.statusCode}")
        }

        val accessExpiresAt = now.plusSeconds(response.expiresInSeconds)
        authContext.accessToken = response.accessToken
        authContext.accessExpiresAt = accessExpiresAt
        // The Keycloak session of this login (`sid`): every later grant continues exactly this one,
        // and an App logout ends it.
        authContext.keycloakSessionId = sessionId
            ?: checkNotNull(sidClaimOf(response.accessToken)) { "Keycloak token for AuthContext $authContextId carries no sid" }
        if (response.refreshToken != null) {
            authContext.refreshToken = response.refreshToken
            authContext.refreshExpiresAt = response.refreshExpiresInSeconds?.let { now.plusSeconds(it) }
        }
        authContextRepository.save(authContext)

        return TokenPair(response.accessToken, accessExpiresAt, authContext.refreshExpiresAt ?: accessExpiresAt)
    }

    /**
     * The same acr/amr [TokenService.mintAccessToken] puts into the mock JWT; the grant copies them
     * onto the Keycloak session. Plain parameters: only the orchestrator's client may call it (ADR-9).
     * Without [sessionId] the grant opens the login's session, with it the grant continues it (ADR-43).
     */
    private fun requestAccountToken(authContext: AuthContext, sessionId: String?): AccountTokenResponse {
        val accountId = checkNotNull(authContext.accountId)
        val account = accountService.findAccount(accountId)
        val evidence = authContext.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }?.toCoreEvidence()
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
