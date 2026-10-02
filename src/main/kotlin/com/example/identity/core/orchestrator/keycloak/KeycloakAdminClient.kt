package com.example.identity.core.orchestrator.keycloak

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.kcmigrate.federatedInvitationUserId
import com.example.identity.kcmigrate.federatedUserId
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

/**
 * The orchestrator's calls into Keycloak: ending a session, clearing up after a deleted account, and
 * the account-token grant. Not a user mirror; Keycloak reads accounts through its user federation
 * (ADR-38). A federated user's Keycloak id is computed ([federatedUserId]), never searched for.
 * Admin calls run as `keycloak-sync.admin-client-id`. The token grant runs as the separate
 * `keycloak-sync.app-client-id`, so the client that mints end-user tokens holds no admin rights.
 */
@Component
@Profile("keycloak")
class KeycloakAdminClient(
    keycloakHttp: KeycloakHttp,
    private val clientAssertions: OrchestratorClientAssertionSigner,
    @Value("\${keycloak-sync.base-url}") private val baseUrl: String,
    @Value("\${keycloak-sync.public-base-url}") private val publicBaseUrl: String,
    @Value("\${keycloak-sync.realm}") private val realm: String,
    @Value("\${keycloak-sync.admin-client-id}") private val adminClientId: String,
    @Value("\${keycloak-sync.app-client-id}") private val appClientId: String,
    private val clock: Clock
) {
    private val log = LoggerFactory.getLogger(KeycloakAdminClient::class.java)
    private val restClient = keycloakHttp.restClient(baseUrl)

    @Volatile
    private var cachedToken: CachedToken? = null

    /**
     * Ends one Keycloak session: the App-channel logout for the session the account-token grant
     * created (docs/07-betrieb.md Abschnitt 3). Deliberately not `users/{id}/logout`, which would
     * also end the account's Web-channel browser sessions. Callers treat it as best-effort.
     */
    fun logoutSession(keycloakSessionId: String) {
        asAdmin { it.delete().uri("/admin/realms/{realm}/sessions/{sessionId}", realm, keycloakSessionId).retrieve().toBodilessEntity() }
    }

    /**
     * The account is gone: Keycloak drops what it keeps locally for this federated user (sessions,
     * login failures, consents, federated storage). Goes through the extension's own endpoint,
     * because Keycloak's `DELETE users/{id}` first looks the user up, which no longer succeeds.
     */
    /**
     * Ends every Keycloak session of an invitation's user (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
     * Keycloak finds the user through the invitation federation even after the invitation has ended.
     */
    fun logoutInvitation(invitation: InvitationId) {
        asAdmin {
            it.post().uri("/admin/realms/{realm}/users/{userId}/logout", realm, federatedInvitationUserId(invitation.value))
                .retrieve().toBodilessEntity()
        }
    }

    fun removeAccount(accountId: AccountId) {
        asAdmin { it.delete().uri("/admin/realms/{realm}/orchestrator-accounts/{accountId}", realm, accountId).retrieve().toBodilessEntity() }
        log.info("Keycloak: removed local state of federated user for accountId={}", accountId)
    }

    /**
     * Mints a Keycloak-signed access token for [accountId] with [acr] and [amr] through the custom
     * account-token grant. Authenticated as the dedicated app-token client, the only one the grant
     * accepts. That client is the orchestrator; there is no second per-account proof (ADR-9).
     * Without [sessionId] the grant opens a new Keycloak session; with it, it continues exactly
     * that session or fails (ADR-43).
     */
    fun requestAccountToken(accountId: AccountId, acr: String?, amr: List<String>, sessionId: String?): AccountTokenResponse {
        val form = "grant_type=$ACCOUNT_TOKEN_GRANT_TYPE" +
            "&${clientAuth(appClientId)}" +
            "&account_id=$accountId" +
            (sessionId?.let { "&session_id=" + URLEncoder.encode(it, StandardCharsets.UTF_8) } ?: "") +
            (acr?.let { "&acr=" + URLEncoder.encode(it, StandardCharsets.UTF_8) } ?: "") +
            "&amr=" + URLEncoder.encode(amr.joinToString(","), StandardCharsets.UTF_8)
        return tokenResponse(form)
    }

    /**
     * Plain `refresh_token` grant against the session [requestAccountToken] created. The caller
     * decides that ACR/AMR are unchanged. The refresh also keeps the session's SSO idle timeout alive.
     */
    fun refreshAccountToken(refreshToken: String): AccountTokenResponse {
        val form = "grant_type=refresh_token" +
            "&${clientAuth(appClientId)}" +
            "&refresh_token=$refreshToken"
        return tokenResponse(form)
    }

    /**
     * Client-Authentisierung per `private_key_jwt` (RFC 7523) statt `client_secret`. Keycloak prueft
     * die Assertion gegen [OrchestratorClientJwksController]; kein geteiltes Geheimnis (wie ADR-7).
     * Als `aud` dient die oeffentliche Realm-Adresse, nicht [baseUrl]: Keycloak vergleicht gegen
     * die Issuer-URL aus seiner Frontend-Konfiguration (KC_HOSTNAME).
     */
    private fun clientAuth(clientId: String): String {
        val assertion = clientAssertions.assertionFor(clientId, "$publicBaseUrl/realms/$realm")
        return "client_id=$clientId" +
            "&client_assertion_type=${URLEncoder.encode(CLIENT_ASSERTION_TYPE, StandardCharsets.UTF_8)}" +
            "&client_assertion=$assertion"
    }

    private fun tokenResponse(form: String): AccountTokenResponse {
        val response = restClient.post()
            .uri("/realms/{realm}/protocol/openid-connect/token", realm)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(form)
            .retrieve()
            .body<Map<String, Any?>>()
            ?: error("Keycloak token endpoint returned no body")
        val accessToken = response["access_token"] as? String ?: error("Keycloak token response has no access_token")
        val expiresInSeconds = (response["expires_in"] as? Number)?.toLong() ?: 60L
        val refreshToken = response["refresh_token"] as? String
        val refreshExpiresInSeconds = (response["refresh_expires_in"] as? Number)?.toLong()
        return AccountTokenResponse(accessToken, expiresInSeconds, refreshToken, refreshExpiresInSeconds)
    }

    /**
     * Runs [call] with the service-account token. A 401 means Keycloak no longer accepts the cached
     * token, although it has not expired yet - typically because the demo rebuilt the realm and its
     * keys. Then the token is dropped and the call repeated exactly once with a fresh one.
     */
    private fun <T> asAdmin(call: (RestClient) -> T): T =
        try {
            call(authorized())
        } catch (e: HttpClientErrorException.Unauthorized) {
            cachedToken = null
            call(authorized())
        }

    /** [restClient] pre-authorized with a valid (cached, auto-refreshed) service-account access token. */
    private fun authorized(): RestClient =
        restClient.mutate().defaultHeader("Authorization", "Bearer ${accessToken()}").build()

    private fun accessToken(): String {
        val current = cachedToken
        if (current != null && clock.instant().isBefore(current.expiresAt)) return current.value

        val form = "grant_type=client_credentials&${clientAuth(adminClientId)}"
        val response = restClient.post()
            .uri("/realms/{realm}/protocol/openid-connect/token", realm)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body(form)
            .retrieve()
            .body<Map<String, Any?>>()
            ?: error("Keycloak service-account token request returned no body")

        val token = response["access_token"] as? String ?: error("Keycloak token response has no access_token")
        val expiresInSeconds = (response["expires_in"] as? Number)?.toLong() ?: 60L
        // A minute of slack, so a cached token does not expire on its way to Keycloak.
        val expiresAt = clock.instant().plusSeconds((expiresInSeconds - 60).coerceAtLeast(5))
        val fresh = CachedToken(token, expiresAt)
        cachedToken = fresh
        return fresh.value
    }

    private data class CachedToken(val value: String, val expiresAt: Instant)

    companion object {
        /** Must match [com.example.identity.kcext.grant.AccountTokenGrantType.GRANT_TYPE] on the keycloak-extension side. */
        const val ACCOUNT_TOKEN_GRANT_TYPE = "urn:identity-demo:account-token"

        /** RFC 7523: signierte Client-Assertion statt client_secret. */
        private const val CLIENT_ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
    }
}

data class AccountTokenResponse(
    val accessToken: String,
    val expiresInSeconds: Long,
    val refreshToken: String? = null,
    val refreshExpiresInSeconds: Long? = null
)
