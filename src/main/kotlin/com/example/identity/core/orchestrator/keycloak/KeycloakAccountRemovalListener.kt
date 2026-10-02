package com.example.identity.core.orchestrator.keycloak

import com.example.identity.core.account.AccountDeleted
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.modulith.events.ApplicationModuleListener

/**
 * The one thing Keycloak has to hear about an account: that it is gone. Everything else it reads
 * through its federation. [KeycloakAdminClient.removeAccount] clears the Keycloak-local state. A
 * failed removal stays an incomplete publication and is retried, so a deleted account's sessions
 * do not silently survive (docs/07-betrieb.md Abschnitt 3a). Removals are idempotent.
 */
@Component
@Profile("keycloak")
class KeycloakAccountRemovalListener(
    private val keycloakAdminClient: KeycloakAdminClient,
) {
    /** A failed removal throws, leaving this listener's publication incomplete - and so retried. */
    @ApplicationModuleListener
    fun onAccountDeleted(event: AccountDeleted) {
        keycloakAdminClient.removeAccount(event.accountId)
    }
}
