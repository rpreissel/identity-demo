package com.example.identity.core.orchestrator.domain

/**
 * Names of the runtime feature flags a strategy may read off [JourneyContext.featureFlags]. One
 * generic set instead of a `JourneyContext` property per flag. `JourneyService` fills it; a
 * strategy never holds the flag service itself (see `IntentStrategy`).
 */
object FeatureFlags {
    /** REGISTER's "Enrollment zuerst" experiment (`FeatureFlagService`, docs/04-orchestrierung.md). */
    const val REGISTER_ENROLL_FIRST = "register-enroll-first"

    /**
     * Keycloak shows the Keycloakify login theme instead of the FreeMarker one (`LoginThemeSwitch`,
     * docs/adr/ADR-041-keycloakify-neben-freemarker.md). No strategy reads it; it shares the store.
     */
    const val KEYCLOAK_LOGIN_KEYCLOAKIFY = "keycloak-login-keycloakify"

    /**
     * The web channel's `loa1` asks Keycloak's password instead of the orchestrator's method
     * selection (`Loa1LoginSwitch`, docs/adr/ADR-042-loa1-anmeldung-umschalten.md). Named after the
     * exception, so no row means the realm's initial state. No strategy reads it either.
     */
    const val KEYCLOAK_LOA1_PASSWORD = "keycloak-loa1-password"
}

/**
 * Implemented by a service that backs a runtime feature flag, to contribute its active flag names
 * ([FeatureFlags]). `JourneyService` collects all providers into [JourneyContext.featureFlags], so a
 * new flag needs no change there.
 */
fun interface FeatureFlagProvider {
    /** Empty when nothing this provider owns is active. */
    fun activeFlags(): Set<String>
}
