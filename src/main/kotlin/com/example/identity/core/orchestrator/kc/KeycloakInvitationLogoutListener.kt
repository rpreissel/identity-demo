package com.example.identity.core.orchestrator.kc

import com.example.identity.contract.tool_api.directory.InvitationEnded
import org.springframework.context.annotation.Profile
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * An ended invitation ends its sessions (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). Keycloak would
 * refuse the next refresh anyway, because the invitation's user is then disabled; this makes it
 * immediate. A failed logout stays an incomplete publication and is retried.
 */
@Component
@Profile("keycloak")
class KeycloakInvitationLogoutListener(
    private val keycloakAdminClient: KeycloakAdminClient,
) {
    @ApplicationModuleListener
    fun onInvitationEnded(event: InvitationEnded) {
        keycloakAdminClient.logoutInvitation(event.invitation)
    }
}
