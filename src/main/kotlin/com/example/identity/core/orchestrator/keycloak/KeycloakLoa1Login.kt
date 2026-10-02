package com.example.identity.core.orchestrator.keycloak

import com.example.identity.kcmigrate.buildAdminClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** What the browser client's `loa1` branch asks for (docs/adr/ADR-042-loa1-anmeldung-umschalten.md). */
enum class Loa1Login {
    /** Keycloak's own password form, reported to the orchestrator right after. */
    KEYCLOAK_PASSWORD,

    /**
     * The orchestrator's method selection, including the QR login without a known account - the
     * realm's initial state.
     */
    ORCHESTRATOR,
}

/**
 * Switches the `loa1` branch of the `orchestrator-browser` flow between its two sets of executions
 * (keycloak-migrations, V1__realm.kc.kts, V2); which one is wanted is `Loa1LoginSwitch`'s business.
 * Writes as the migration client ([KeycloakMigrationToken]), like [KeycloakRealmLoginTheme].
 */
@Component
@Profile("keycloak")
class KeycloakLoa1Login(
    private val keycloakHttp: KeycloakHttp,
    private val setupSource: ConfiguredKeycloakSetupSource,
    private val migrationToken: KeycloakMigrationToken,
    @Value("\${keycloak-migrate.base-url}") private val baseUrl: String,
) {

    fun apply(login: Loa1Login) {
        val kc = buildAdminClient(baseUrl, migrationToken::accessToken, insecure = keycloakHttp.trustSelfSigned)
        try {
            val flows = kc.realm(setupSource.selected().realm.realmName).flows()
            val executions = flows.getExecutions(LOA1_SUBFLOW)
            // The new set first, the old one after: in between both run, never neither, so no
            // sign-in ever passes loa1 without an authenticator.
            val updates = requirements(login).map { (providerId, requirement) ->
                val execution = executions.singleOrNull { it.providerId == providerId }
                    ?: error("Keycloak-Flow $LOA1_SUBFLOW hat keine Execution $providerId - Realm-Aufbau pruefen")
                execution to requirement
            }
            updates.filter { (execution, requirement) -> execution.requirement != requirement }
                .forEach { (execution, requirement) ->
                    execution.requirement = requirement
                    flows.updateExecutions(LOA1_SUBFLOW, execution)
                }
        } finally {
            kc.close()
        }
    }

    companion object {
        /** The `loa1` subflow's alias in the realm setup. */
        const val LOA1_SUBFLOW = "orchestrator-loa-1"

        private val KEYCLOAK_PASSWORD_EXECUTIONS = listOf("auth-username-password-form", "orchestrator-update-authenticator")
        private val ORCHESTRATOR_EXECUTIONS = listOf("orchestrator-authenticator")

        /** Each execution of the subflow with its requirement for [login], the enabled ones first. */
        fun requirements(login: Loa1Login): List<Pair<String, String>> {
            val (on, off) = when (login) {
                Loa1Login.KEYCLOAK_PASSWORD -> KEYCLOAK_PASSWORD_EXECUTIONS to ORCHESTRATOR_EXECUTIONS
                Loa1Login.ORCHESTRATOR -> ORCHESTRATOR_EXECUTIONS to KEYCLOAK_PASSWORD_EXECUTIONS
            }
            return on.map { it to "REQUIRED" } + off.map { it to "DISABLED" }
        }
    }
}
