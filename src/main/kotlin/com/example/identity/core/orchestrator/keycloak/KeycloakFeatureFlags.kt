package com.example.identity.core.orchestrator.keycloak

/**
 * Keycloak's runtime switches. They live in the same store as the journey flags
 * (`FeatureFlagService`), but no strategy reads them, so they are not part of the domain's
 * [com.example.identity.core.orchestrator.domain.JourneyFeatureFlag].
 */
object KeycloakFeatureFlags {
    /**
     * The web channel's `loa1` asks Keycloak's password instead of the orchestrator's method
     * selection (`Loa1LoginSwitch`, docs/adr/ADR-042-loa1-anmeldung-umschalten.md). Named after the
     * exception, so no row means the realm's initial state.
     */
    const val LOA1_PASSWORD = "keycloak-loa1-password"
}
