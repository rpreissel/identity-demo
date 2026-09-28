package com.example.identity.core.orchestrator

import java.util.concurrent.atomic.AtomicBoolean
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/**
 * Ob der Orchestrator bereits Traffic bedienen darf. Tomcat oeffnet den Port, bevor die
 * ApplicationRunner (die Keycloak-Migrationen) durchgelaufen sind. [ReadinessGateFilter] blockt
 * dieses Fenster.
 */
interface ReadinessState {
    val isReady: Boolean
}

/** Default-Profil (ohne Keycloak) hat keine Migrationen abzuwarten - immer bereit. */
@Component
@Profile("!keycloak")
class AlwaysReadyState : ReadinessState {
    override val isReady = true
}

/**
 * Startet als "nicht bereit", schon beim Erzeugen der Bean und damit vor dem Oeffnen des Ports.
 * So gibt es kein Fenster mit offenem Port ohne Gate.
 */
@Component
@Profile("keycloak")
class KeycloakGatedReadinessState : ReadinessState {
    private val ready = AtomicBoolean(false)
    override val isReady: Boolean get() = ready.get()
    fun markReady() = ready.set(true)
}
