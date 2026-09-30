package com.example.identity.core.orchestrator.api.v1.kc

import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.contract.tool_api.envelope.API_V1
import com.example.identity.core.orchestrator.dpop.buildRequestUrl
import com.example.identity.core.orchestrator.kc.KcInvitationView
import com.example.identity.core.orchestrator.kc.KcInvitationViews
import com.example.identity.core.orchestrator.kc.PeerAuthValidationException
import com.example.identity.core.orchestrator.kc.PeerAuthValidator
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/**
 * Keycloak's second user federation reads invitations here (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md),
 * by their identity only: there is no search. The assertion's `channel_binding` names the invitation
 * looked up, and the answer is signed like every answer to Keycloak.
 */
@RestController
@Tag(name = "KC invitation lookup", description = "Read-through invitation lookup for Keycloak's user federation")
@SecurityRequirement(name = "kc-peer-auth")
class KcInvitationLookupController(
    private val peerAuthValidator: PeerAuthValidator,
    private val views: KcInvitationViews,
) {

    @GetMapping("$API_V1/kc/invitations/{invitation}")
    @Operation(summary = "The invitation as Keycloak shows it, as a user of its own")
    fun byInvitation(
        @PathVariable invitation: InvitationId,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<KcInvitationView> {
        val token = authorization?.trim()?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it }
            ?: throw PeerAuthValidationException("Missing Authorization header")
        val assertion = peerAuthValidator.validate(token, httpRequest.method, buildRequestUrl(httpRequest))
        if (assertion.channelBinding != invitation.value) {
            throw PeerAuthValidationException("Peer-auth channel_binding does not match this lookup")
        }
        return views.byInvitation(invitation)?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }
}
