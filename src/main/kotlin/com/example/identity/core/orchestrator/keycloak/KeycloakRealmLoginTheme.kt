package com.example.identity.core.orchestrator.keycloak

import com.example.identity.kcmigrate.buildAdminClient
import org.keycloak.representations.idm.RealmRepresentation
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Which of the two login themes the realm shows (docs/adr/ADR-041-keycloakify-neben-freemarker.md). */
enum class LoginTheme {
    /** The hand-written FreeMarker theme - the realm's `loginTheme` from the setup. */
    FREEMARKER,

    /** The Keycloakify theme; its parent is the FreeMarker theme, so pages it lacks still show. */
    KEYCLOAKIFY,
}

/**
 * Sets the realm's login theme in Keycloak; which theme is wanted is `LoginThemeSwitch`'s business.
 * Writes as the migration client ([KeycloakMigrationToken]), so `orchestrator-admin` keeps just
 * manage-users.
 */
@Component
@Profile("keycloak")
class KeycloakRealmLoginTheme(
    private val keycloakHttp: KeycloakHttp,
    private val setupSource: ConfiguredKeycloakSetupSource,
    private val migrationToken: KeycloakMigrationToken,
    @Value("\${keycloak-migrate.base-url}") private val baseUrl: String,
) {

    fun apply(theme: LoginTheme) {
        val realm = setupSource.selected().realm
        val themeName = when (theme) {
            LoginTheme.FREEMARKER -> realm.loginTheme
            LoginTheme.KEYCLOAKIFY -> KEYCLOAKIFY_THEME
        }
        val kc = buildAdminClient(baseUrl, migrationToken::accessToken, insecure = keycloakHttp.trustSelfSigned)
        try {
            // Only the one field: the admin API leaves everything not sent as it is.
            kc.realm(realm.realmName).update(RealmRepresentation().apply { loginTheme = themeName })
        } finally {
            kc.close()
        }
    }

    companion object {
        /** keycloak-theme/vite.config.ts `themeName` - the JAR in /opt/keycloak/providers. */
        const val KEYCLOAKIFY_THEME = "orchestrator-keycloakify"
    }
}
