package com.example.identity.core.orchestrator.kc

import com.example.identity.kcmigrate.buildAdminClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.time.Instant

/** The two realm clients whose sessions stand for a channel. */
enum class KeycloakSessionClient {
    /** The browser client: the website's sign-ins. */
    WEBSITE,

    /** The app token client: the sessions the account-token grant creates for the App channel. */
    APP,
}

/** One open Keycloak user session, as the admin API reports it. */
data class KeycloakUserSession(
    val sessionId: String,
    val username: String?,
    /** Keycloak's user id; for a federated account `f:<component>:<accountId>`. */
    val userId: String?,
    val start: Instant,
    val lastAccess: Instant,
)

/** A client's open sessions: [count] all of them, [newest] at most the requested number, newest first. */
data class KeycloakClientSessions(val clientId: String, val count: Long, val newest: List<KeycloakUserSession>)

/** Reads Keycloak's open user sessions per client, for the operator's session overview. */
fun interface KeycloakUserSessions {
    /** Throws when Keycloak cannot be asked; the caller reports that instead. */
    fun openSessions(limit: Int): Map<KeycloakSessionClient, KeycloakClientSessions>
}

/**
 * Asks the admin API as the migration client ([KeycloakMigrationToken]), like
 * [KeycloakRealmLoginTheme], so `orchestrator-admin` keeps just manage-users.
 */
@Component
@Profile("keycloak")
class KeycloakAdminUserSessions(
    private val keycloakHttp: KeycloakHttp,
    private val setupSource: ConfiguredKeycloakSetupSource,
    private val migrationToken: KeycloakMigrationToken,
    @Value("\${keycloak-migrate.base-url}") private val baseUrl: String,
) : KeycloakUserSessions {

    override fun openSessions(limit: Int): Map<KeycloakSessionClient, KeycloakClientSessions> {
        val realm = setupSource.selected().realm
        val clientIds = mapOf(
            KeycloakSessionClient.WEBSITE to realm.browserClientId,
            KeycloakSessionClient.APP to realm.appTokenClientId,
        )
        val kc = buildAdminClient(baseUrl, migrationToken::accessToken, insecure = keycloakHttp.trustSelfSigned)
        try {
            val clients = kc.realm(realm.realmName).clients()
            return clientIds.mapValues { (_, clientId) ->
                val id = clients.findByClientId(clientId).singleOrNull()?.id
                    ?: error("Keycloak-Client $clientId fehlt im Realm ${realm.realmName}")
                val client = clients.get(id)
                // The admin API sorts by nothing useful; the newest need the whole page.
                val sessions = client.getUserSessions(0, FETCH_MAX).map {
                    KeycloakUserSession(it.id, it.username, it.userId, Instant.ofEpochMilli(it.start), Instant.ofEpochMilli(it.lastAccess))
                }
                KeycloakClientSessions(
                    clientId = clientId,
                    count = client.applicationSessionCount["count"]?.toLong() ?: sessions.size.toLong(),
                    newest = sessions.sortedByDescending { it.start }.take(limit),
                )
            }
        } finally {
            kc.close()
        }
    }

    private companion object {
        /** Enough for a demo; beyond it "newest" means newest of the first page. */
        const val FETCH_MAX = 500
    }
}
