package com.example.identity.contract.tool_api.directory

import com.example.identity.contract.tool_api.claims.AcrLevel

/**
 * The invitations of the person register (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md): the
 * register issues them, sends the one-time password by letter and ends them; we only ask, as for
 * the Freischaltcode (ADR-31). An invitation is identified by SHA-256 over person, one-time
 * password and process, which the register and the business system compute alike.
 */
interface Invitations {
    /**
     * The open invitation of [personId] whose one-time password is [code], else `null`. The person
     * does not type the process, so every open invitation of that person is tried.
     */
    fun redeem(personId: String, code: String): InvitationGrant?

    /** What Keycloak shows as the invitation's user; `null` for an unknown invitation. */
    fun find(invitation: String): InvitationView?
}

/** What an accepted one-time password opens: one process, at one level. */
data class InvitationGrant(val invitation: String, val process: String, val acr: AcrLevel)

data class InvitationView(
    val invitation: String,
    val process: String,
    val personId: String,
    /** Neither completed nor revoked nor expired: a login with it is possible right now. */
    val open: Boolean,
)

/**
 * The register ended an invitation on purpose (the process is done, or it was withdrawn), like
 * [PersonChanged] for a person (ADR-34). Listeners end the sessions it opened; an expiry needs no
 * event, the tokens simply run out.
 */
data class InvitationEnded(val invitation: String)
