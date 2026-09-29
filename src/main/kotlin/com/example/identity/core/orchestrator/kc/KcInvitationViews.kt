package com.example.identity.core.orchestrator.kc

import com.example.identity.contract.tool_api.directory.PersonMasterData
import com.example.identity.contract.tool_api.directory.Invitations
import org.springframework.stereotype.Component

/**
 * An invitation as Keycloak sees it: a user of its own, never an account
 * (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). It carries the same person attributes as an
 * account of that person, so its tokens look the same, plus the two markers that bind them to one
 * process. [enabled] is false once the invitation is completed, revoked or expired; Keycloak then
 * refuses every further token.
 */
data class KcInvitationView(
    val invitation: String,
    val username: String,
    val enabled: Boolean,
    val firstName: String,
    val lastName: String,
    val attributes: Map<String, String>,
)

@Component
class KcInvitationViews(
    private val invitations: Invitations,
    private val personMasterData: PersonMasterData,
) {
    fun byInvitation(invitation: String): KcInvitationView? {
        val view = invitations.find(invitation) ?: return null
        val person = personMasterData.masterDataOf(view.personId)
        return KcInvitationView(
            invitation = view.invitation,
            username = "$USERNAME_PREFIX${view.invitation}",
            enabled = view.open,
            firstName = person?.givenNames ?: UNIDENTIFIED_FIRST_NAME,
            lastName = person?.familyName ?: UNIDENTIFIED_LAST_NAME,
            attributes = masterDataAttributes(view.personId, person, emptyMap()) +
                mapOf(INVITATION_ATTRIBUTE to view.invitation, PROCESS_ATTRIBUTE to view.process),
        )
    }

    companion object {
        const val USERNAME_PREFIX = "invitation-"
        /** Mapped to the `invitation` claim. */
        const val INVITATION_ATTRIBUTE = "orchestratorInvitation"
        /** Mapped to the `process` claim: a token carrying it is good for that one process only. */
        const val PROCESS_ATTRIBUTE = "orchestratorProcess"
    }
}
