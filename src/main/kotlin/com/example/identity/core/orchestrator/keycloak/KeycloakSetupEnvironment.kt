package com.example.identity.core.orchestrator.keycloak

import com.example.identity.kcmigrate.KeycloakSetup
import org.springframework.boot.SpringApplication
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Leitet die Keycloak-Laufzeitwerte des Orchestrators aus dem gewaehlten Migrations-Parametersatz
 * ab. Realm, Adresse und Client-Ids gibt es so nur einmal; Migration und Betrieb koennen nicht
 * gegen verschiedene Realms laufen (ADR-25). Ein [EnvironmentPostProcessor], weil die Werte in der
 * Environment stehen muessen, bevor `@Value`-Platzhalter beim Erzeugen der Beans aufgeloest werden.
 */
class KeycloakSetupEnvironment : EnvironmentPostProcessor, Ordered {

    /**
     * Nach [org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor]: erst dann
     * sind application-keycloak.yml und die aktiven Profile geladen.
     */
    override fun getOrder() = Ordered.HIGHEST_PRECEDENCE + 20

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        if (PROFILE !in environment.activeProfiles) return
        val setup = resolve(environment)
        val realms = "${setup.access.keycloakBaseUrl}/realms/${setup.realm.realmName}"
        environment.propertySources.addFirst(
            MapPropertySource(
                "migration-setup-derived",
                mapOf(
                    // Wohin die Migration selbst schreibt. Ein Zugang steht hier nicht: sie meldet
                    // sich per signierter Assertion an (KeycloakMigrationToken).
                    "keycloak-migrate.base-url" to setup.access.keycloakBaseUrl,
                    // Wie Keycloak den Orchestrator erreicht - fuer ProductionModeCheck (https-Pflicht).
                    "keycloak-setup.orchestrator-base-url" to setup.realm.orchestratorBaseUrl,
                    // Nur fuer die Verbindungen zu Keycloak (KeycloakHttp), nie JVM-weit.
                    "keycloak-tls.trust-self-signed" to setup.access.trustSelfSignedCertificate.toString(),
                    // Account-Sync: dasselbe Realm, dieselben Clients wie die Migration sie anlegt.
                    "keycloak-sync.base-url" to setup.access.keycloakBaseUrl,
                    "keycloak-sync.realm" to setup.realm.realmName,
                    "keycloak-sync.public-base-url" to setup.access.publicKeycloakBaseUrl,
                    "keycloak-sync.admin-client-id" to setup.realm.adminApiClientId,
                    "keycloak-sync.app-client-id" to setup.realm.appTokenClientId,
                    // Web-Kanal im Browser (ServerInfo.keycloak): der Client, den die Migration
                    // anlegt, statt einer Kopie im Frontend.
                    "keycloak-web.browser-client-id" to setup.realm.browserClientId,
                    // Peer-Auth: iss/aud muessen dem entsprechen, was die Extension signiert; die
                    // Migration setzt es aus denselben Feldern.
                    "keycloak.peer-auth.issuer" to setup.realm.peerAuthIssuer,
                    "keycloak.peer-auth.audience" to setup.realm.peerAuthAudience,
                    "keycloak.peer-auth.jwks-uri" to "$realms/orchestrator-jwks/.well-known/jwks.json",
                ),
            ),
        )
    }

    /**
     * Bindet dieselbe Klasse, die spaeter als Bean die Migration steuert. So laufen Migration und
     * diese Properties sicher mit demselben Parametersatz.
     */
    private fun resolve(environment: ConfigurableEnvironment): KeycloakSetup =
        Binder.get(environment)
            .bind(ConfiguredKeycloakSetupSource.PREFIX, Bindable.of(ConfiguredKeycloakSetupSource::class.java))
            .orElseGet { ConfiguredKeycloakSetupSource() }
            .selected()

    private companion object {
        const val PROFILE = "keycloak"
    }
}
