package com.example.identity.core.orchestrator.keycloak

import com.example.identity.demo.demo_mode.OnlyInDemoMode
import com.example.identity.core.account.AccountService
import com.example.identity.kcmigrate.buildAdminClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

/**
 * Ends every session in the realm - for the demo only, where all its users are orchestrator
 * accounts. Writes as the migration client, like [KeycloakRealmLoginTheme].
 */
@Component
@Profile("keycloak")
class KeycloakRealmSessions(
    private val keycloakHttp: KeycloakHttp,
    private val setupSource: ConfiguredKeycloakSetupSource,
    private val migrationToken: KeycloakMigrationToken,
    @Value("\${keycloak-migrate.base-url}") private val baseUrl: String,
) {
    fun logoutAll() {
        val kc = buildAdminClient(baseUrl, migrationToken::accessToken, insecure = keycloakHttp.trustSelfSigned)
        try {
            kc.realm(setupSource.selected().realm.realmName).logoutAll()
        } finally {
            kc.close()
        }
    }
}

/**
 * In demo mode, a start without a single account (fresh volume, or a database the demo reset
 * rebuilt) means every Keycloak session belongs to an account that no longer exists. Keycloak
 * cannot even list such sessions, so they end here.
 */
@Component
@Profile("keycloak")
@OnlyInDemoMode
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class KeycloakOrphanSessionsAtStart(
    private val accountService: AccountService,
    private val realmSessions: KeycloakRealmSessions,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(KeycloakOrphanSessionsAtStart::class.java)

    override fun run(args: ApplicationArguments) {
        if (accountService.allAccountIds().isNotEmpty()) return
        // Leftover sessions must not keep the orchestrator from starting.
        runCatching { realmSessions.logoutAll() }
            .onSuccess { log.info("Keycloak: keine Konten vorhanden, alle Sitzungen im Realm beendet.") }
            .onFailure { log.warn("Keycloak: Sitzungen im Realm konnten nicht beendet werden", it) }
    }
}
