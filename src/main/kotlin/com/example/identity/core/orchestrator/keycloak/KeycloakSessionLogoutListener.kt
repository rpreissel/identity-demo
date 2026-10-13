package com.example.identity.core.orchestrator.keycloak

import org.springframework.context.annotation.Profile
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * An App channel logged out and held its own Keycloak session from the account-token grant; that
 * session ends in Keycloak too. Asynchronous after commit, on a thread of its own: Keycloak answers
 * the call with a back-channel logout to this orchestrator, and the logout's request and database
 * connection must not wait for that. A failed call stays an incomplete publication and is retried
 * (docs/07-betrieb.md Abschnitt 3a); ending a session that is gone already is no failure.
 */
@Component
@Profile("keycloak")
class KeycloakSessionLogoutListener(private val keycloakAdminClient: KeycloakAdminClient) {

    @ApplicationModuleListener
    fun onChannelLoggedOut(event: KeycloakSessionEnded) {
        keycloakAdminClient.logoutSession(event.keycloakSessionId)
    }
}

/**
 * "This channel's own Keycloak session is over." Published by the logout transition of an App
 * channel only; a Web channel's logout stays with Keycloak (docs/07-betrieb.md Abschnitt 3).
 */
data class KeycloakSessionEnded(val keycloakSessionId: String)
