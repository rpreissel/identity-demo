package com.example.identity.core.orchestrator.api.v1.keycloak

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.core.orchestrator.channel.KeycloakChannelService
import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.keycloak.PeerAuthAssertion
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidationException
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidator
import com.example.identity.contract.tool_api.envelope.API_V1
import com.example.identity.core.orchestrator.dpop.buildRequestUrl
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Keycloak reports a logout here (ADR-39), because the Web channel's logout is Keycloak's own
 * (docs/07-betrieb.md Abschnitt 3). Called fire-and-forget after the logout. The assertion's
 * `channel_binding` is the account id or invitation from the path, as with the lookups.
 */
@RestController
@Tag(name = "Keycloak sign-out", description = "Keycloak reports a logout for the sign-in log")
@SecurityRequirement(name = "kc-peer-auth")
class KeycloakSignOutController(
    private val peerAuthValidator: PeerAuthValidator,
    private val keycloakChannelService: KeycloakChannelService,
) {

    @PostMapping("$API_V1/kc/accounts/{accountId}/sign-outs")
    @Operation(
        summary = "Keycloak ended one session of this account",
        responses = [ApiResponse(responseCode = "204", description = "Recorded - no body.")]
    )
    fun signedOut(
        @PathVariable accountId: AccountId,
        @RequestParam kcSessionId: String,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Void> {
        val assertion = validate(authorization, httpRequest)
        if (assertion.channelBinding != accountId.toString()) {
            throw PeerAuthValidationException("Peer-auth channel_binding does not match this account")
        }
        keycloakChannelService.signedOutAtKeycloak(Subject.Account(accountId), kcSessionId)
        return ResponseEntity.noContent().build()
    }

    /** The same for a process access (ADR-48); the binding names the invitation. */
    @PostMapping("$API_V1/kc/invitations/{invitation}/sign-outs")
    @Operation(
        summary = "Keycloak ended one session of this invitation",
        responses = [ApiResponse(responseCode = "204", description = "Recorded - no body.")]
    )
    fun invitationSignedOut(
        @PathVariable invitation: InvitationId,
        @RequestParam kcSessionId: String,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<Void> {
        val assertion = validate(authorization, httpRequest)
        if (assertion.channelBinding != invitation.value) {
            throw PeerAuthValidationException("Peer-auth channel_binding does not match this invitation")
        }
        keycloakChannelService.signedOutAtKeycloak(Subject.Invitation(invitation), kcSessionId)
        return ResponseEntity.noContent().build()
    }

    private fun validate(authorization: String?, httpRequest: HttpServletRequest): PeerAuthAssertion {
        val token = authorization?.trim()?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it }
            ?: throw PeerAuthValidationException("Missing Authorization header")
        return peerAuthValidator.validate(token, httpRequest.method, buildRequestUrl(httpRequest))
    }
}
