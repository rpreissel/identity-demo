package com.example.identity.tools.auth_invite.internal

import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.directory.Invitations
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=auth-invite-lookup (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). The controller resolves the
 * number to a person and says whether that person is rate-limited; this handler asks the person
 * register whether the one-time password opens one of that person's invitations. A code alone never opens anything: it has to belong
 * to the person named.
 */
@Component
class AuthInviteLookupToolHandler(
    private val sessions: ToolSessionData,
    private val invitations: Invitations,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.save(toolSessionId, AuthInviteLookupToolSession())
        return outcomeFor()
    }

    /**
     * [personId] is null for an unknown number. A rate-limited person, an unknown number and a wrong
     * code look the same to the client, so none of them tells which numbers exist.
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, kvnr: String?, partnerNumber: String?, code: String?, personId: PartnerNumber?, rateLimited: Boolean): ToolOutcome {
        sessions.require<AuthInviteLookupToolSession>(toolSessionId)

        return when (val decision = AuthInviteLookupFlow.decide(AuthInviteInput(kvnr, partnerNumber, code))) {
            is AuthInviteDecision.Incomplete -> outcomeFor(decision.missingFields)
            is AuthInviteDecision.Check -> {
                val grant = if (personId != null && !rateLimited) invitations.redeem(personId, decision.code) else null
                if (grant == null || personId == null) {
                    ToolOutcome.Failed.AccountLookupAuth(Text("Nummer oder Einmalkennwort ungueltig"), attempted = personId?.let(Attempted::Person))
                } else {
                    ToolOutcome.Completed.Authenticated(
                        achievedAcr = grant.acr,
                        subject = Subject.Invitation(grant.invitation),
                    )
                }
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome.InProgress {
        sessions.require<AuthInviteLookupToolSession>(toolSessionId)
        return outcomeFor()
    }

    private fun outcomeFor(missingFields: List<String> = AuthInviteLookupFlow.ALL_FIELDS): ToolOutcome.InProgress {
        return AuthInviteLookupFlow.describe(missingFields).inProgress()
    }
}
