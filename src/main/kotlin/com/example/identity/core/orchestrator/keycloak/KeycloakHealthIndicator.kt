package com.example.identity.core.orchestrator.keycloak

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.health.contributor.AbstractHealthIndicator
import org.springframework.boot.health.contributor.Health
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * `keycloak` in `/actuator/health` (docs/07-betrieb.md Abschnitt 7): whether Keycloak answers its
 * public realm endpoint. Not part of the readiness group: the App channel works without Keycloak,
 * so this is for alerting, not for routing.
 */
@Component("keycloak")
@Profile("keycloak")
class KeycloakHealthIndicator(
    keycloakHttp: KeycloakHttp,
    @Value("\${keycloak-sync.base-url}") baseUrl: String,
    @Value("\${keycloak-sync.realm}") private val realm: String,
) : AbstractHealthIndicator("Keycloak is not reachable") {
    private val restClient = keycloakHttp.restClient(baseUrl)

    override fun doHealthCheck(builder: Health.Builder) {
        restClient.get().uri("/realms/{realm}", realm).retrieve().toBodilessEntity()
        builder.up()
    }
}
