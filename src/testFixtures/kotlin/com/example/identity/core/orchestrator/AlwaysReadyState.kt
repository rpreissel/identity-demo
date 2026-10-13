package com.example.identity.core.orchestrator

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Without Keycloak there are no migrations to wait for: always ready. Counterpart of [KeycloakGatedReadinessState]. */
@Component
@Profile("!keycloak")
class AlwaysReadyState : ReadinessState {
    override val isReady = true
}
