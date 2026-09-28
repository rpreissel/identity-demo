package com.example.identity.core.orchestrator.kc

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * An App channel logged out and held its own Keycloak session from the account-token grant; that
 * session ends in Keycloak too. After commit, so no network call holds the journey transaction
 * open and a rolled-back logout ends nothing. A plain listener without retry: a session not ended
 * early expires within minutes, unlike the state [KeycloakAccountRemovalListener] clears.
 */
@Component
@Profile("keycloak")
class KeycloakSessionLogoutListener(private val keycloakAdminClient: KeycloakAdminClient) {

    private val log = LoggerFactory.getLogger(KeycloakSessionLogoutListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onChannelLoggedOut(event: KeycloakSessionEnded) {
        runCatching { keycloakAdminClient.logoutSession(event.keycloakSessionId) }
            .onFailure { log.warn("Keycloak session logout failed for {}", event.keycloakSessionId, it) }
    }
}

/**
 * "This channel's own Keycloak session is over." Published by the logout transition of an App
 * channel only; a Web channel's logout stays with Keycloak (docs/07-betrieb.md Abschnitt 3).
 */
data class KeycloakSessionEnded(val keycloakSessionId: String)
