package com.example.identity.core.orchestrator.kc

import com.example.identity.demo.demo_mode.DemoMode
import com.example.identity.kcmigrate.MigrationFile
import com.example.identity.kcmigrate.MigrationRunner
import com.example.identity.kcmigrate.MigrationStepFailedException
import com.example.identity.kcmigrate.buildAdminClient
import com.example.identity.core.orchestrator.KeycloakGatedReadinessState
import org.slf4j.LoggerFactory
import org.springframework.modulith.events.IncompleteEventPublications
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * Wendet beim Start alle .kc.kts-Migrationen aus dem keycloak-migrations-Jar an (ADR-25). Tomcat
 * nimmt schon vor den ApplicationRunnern Requests an; bis zum Ende blockt [ReadinessGateFilter]
 * sie mit 503. Meldet sich per [KeycloakMigrationToken] an, weil der Admin-Client des Betriebs erst
 * durch die Migrationen entsteht. Laeuft vor jedem anderen Runner, der das Realm braucht.
 */
@Component
@Profile("keycloak")
@Order(Ordered.HIGHEST_PRECEDENCE)
class KeycloakMigrationRunnerStartup(
    private val keycloakHttp: KeycloakHttp,
    private val readinessState: KeycloakGatedReadinessState,
    private val paramsSource: ConfiguredKeycloakSetupSource,
    private val migrationToken: KeycloakMigrationToken,
    @Value("\${keycloak-migrate.base-url}") private val baseUrl: String,
    // Grosszuegig, weil Keycloak im selben Pod erst sein eigenes Schema aufbaut.
    @Value("\${keycloak-migrate.wait-timeout:PT5M}") private val waitTimeout: Duration,
    // Eine geaenderte Migration darf das Realm nur im Demomodus neu aufbauen. Im Betrieb verwuerfe
    // das Sitzungen, Nutzer-IDs und Credentials; der Start bricht dann ab.
    private val demoMode: DemoMode,
    private val clock: Clock,
    // Im Profil keycloak ist republish-outstanding-events-on-restart aus; zugestellt wird hier,
    // wenn das Realm steht (application-keycloak.yml).
    private val incompletePublications: IncompleteEventPublications,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(KeycloakMigrationRunnerStartup::class.java)

    override fun run(args: ApplicationArguments) {
        val setup = paramsSource.selected()
        val migrations = loadMigrations()
        log.info(
            "Keycloak-Migrationen: wende {} auf Realm '{}' an (Variante '{}')",
            migrations.map { it.name }, setup.realm.realmName, paramsSource.variant,
        )
        // Antwortet der oeffentliche Endpunkt des Master-Realms, laeuft Keycloak samt Datenbank.
        val probe = keycloakHttp.restClient(baseUrl)
        AwaitReachable(waitTimeout, clock::instant).await("Keycloak unter $baseUrl") {
            probe.get().uri("/realms/master").retrieve().toBodilessEntity()
        }
        val kc = buildAdminClient(baseUrl, migrationToken::accessToken, insecure = keycloakHttp.trustSelfSigned)
        val runner = MigrationRunner(kc, setup.realm, migrations, onRealmCreated = migrationToken::invalidate, allowRealmReset = demoMode.on)
        try {
            runner.up()
        } catch (e: MigrationStepFailedException) {
            log.error("Keycloak-Migration {} fehlgeschlagen - rolle sie zurück und breche den Start ab", e.fileName, e)
            runCatching { runner.down(e.fileVersion) }
                .onFailure { rollbackError ->
                    log.error("Rollback von Migration {} zusätzlich fehlgeschlagen", e.fileName, rollbackError)
                }
            throw e
        } finally {
            kc.close()
        }
        log.info("Keycloak-Migrationen abgeschlossen.")
        readinessState.markReady()
        incompletePublications.resubmitIncompletePublications { true }
    }

    /**
     * Die Migrationen kommen als Ressourcen aus dem Jar, zu dessen Code sie gehoeren. `classpath*:`,
     * damit auch mehrere Jars durchsucht werden.
     */
    private fun loadMigrations(): List<MigrationFile> {
        val resources = PathMatchingResourcePatternResolver().getResources(MIGRATIONS_PATTERN)
        val migrations = resources.mapNotNull { resource ->
            val name = resource.filename ?: return@mapNotNull null
            MigrationFile.parse(name, resource.inputStream.use { it.reader().readText() })
        }
        // Ein leerer Lauf ergaebe ein Realm ohne Flows und Clients.
        if (migrations.isEmpty()) error("Keine Migrationen unter $MIGRATIONS_PATTERN gefunden")
        return migrations
    }

    private companion object {
        const val MIGRATIONS_PATTERN = "classpath*:keycloak-migrations/*.kc.kts"
    }
}
