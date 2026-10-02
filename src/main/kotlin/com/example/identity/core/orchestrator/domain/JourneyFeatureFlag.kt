package com.example.identity.core.orchestrator.domain

/**
 * A runtime switch a strategy may read off [JourneyContext.featureFlags]. One generic set instead of
 * a `JourneyContext` property per flag. `JourneyService` fills it; a strategy never holds the flag
 * service itself (see `IntentStrategy`). [key] is the flag's row in the store, which Keycloak's
 * switches (`KeycloakFeatureFlags`) share without ever reaching a strategy.
 */
enum class JourneyFeatureFlag(val key: String) {
    /** REGISTER's "Enrollment zuerst" experiment (`FeatureFlagService`, docs/04-orchestrierung.md). */
    REGISTER_ENROLL_FIRST("register-enroll-first");

    companion object {
        /** Null for a key no strategy reads, such as a Keycloak switch. */
        fun ofKey(key: String): JourneyFeatureFlag? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Implemented by a service that backs a runtime feature flag, to contribute its active flags.
 * `JourneyService` collects all providers into [JourneyContext.featureFlags], so a new flag needs no
 * change there.
 */
fun interface FeatureFlagProvider {
    /** Empty when nothing this provider owns is active. */
    fun activeFlags(): Set<JourneyFeatureFlag>
}
