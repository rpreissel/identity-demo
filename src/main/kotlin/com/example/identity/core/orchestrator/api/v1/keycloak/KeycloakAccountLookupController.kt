package com.example.identity.core.orchestrator.api.v1.keycloak

import com.example.identity.core.orchestrator.keycloak.peerAuthBodySha256
import com.example.identity.core.orchestrator.keycloak.peerAuthTarget
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.keycloak.KeycloakAccountView
import com.example.identity.core.orchestrator.keycloak.KeycloakAccountViews
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidationException
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidator
import com.example.identity.contract.tool_api.envelope.API_V1
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Keycloak's user federation reads accounts here instead of holding a copy (ADR-38). Every lookup
 * is a single indexed read, and there is deliberately no "list all". The assertion's
 * `channel_binding` names what is looked up: the account id for [byId], [LOOKUP_BINDING] for a search
 * by address. The answer is signed, because it decides which user Keycloak logs in.
 */
@RestController
@Tag(name = "Keycloak account lookup", description = "Read-through account lookup for Keycloak's user federation")
@SecurityRequirement(name = "kc-peer-auth")
class KeycloakAccountLookupController(
    private val peerAuthValidator: PeerAuthValidator,
    private val views: KeycloakAccountViews,
) {

    @GetMapping("$API_V1/kc/accounts/{accountId}")
    @Operation(summary = "The account as Keycloak shows it, by account id")
    fun byId(
        @PathVariable accountId: AccountId,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<KeycloakAccountView> {
        validatePeerAuth(authorization, httpRequest, expectedBinding = accountId.toString())
        return views.byAccountId(accountId).toResponse()
    }

    @GetMapping("$API_V1/kc/accounts")
    @Operation(summary = "The account as Keycloak shows it, by exact email or username - never a list")
    fun search(
        @RequestParam(required = false) email: String?,
        @RequestParam(required = false) username: String?,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<KeycloakAccountView> {
        validatePeerAuth(authorization, httpRequest, expectedBinding = LOOKUP_BINDING)
        val view = when {
            email != null && username == null -> views.byEmail(email)
            username != null && email == null -> views.byUsername(username)
            else -> return ResponseEntity.badRequest().build()
        }
        return view.toResponse()
    }

    private fun KeycloakAccountView?.toResponse(): ResponseEntity<KeycloakAccountView> =
        this?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    private fun validatePeerAuth(authorization: String?, httpRequest: HttpServletRequest, expectedBinding: String) {
        val token = authorization?.trim()?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it }
            ?: throw PeerAuthValidationException("Missing Authorization header")
        val assertion = peerAuthValidator.validate(token, httpRequest.method, peerAuthTarget(httpRequest), peerAuthBodySha256(httpRequest))
        if (assertion.channelBinding != expectedBinding) {
            throw PeerAuthValidationException("Peer-auth channel_binding does not match this lookup")
        }
    }

    companion object {
        /** The `channel_binding` of a search by address - there is no account id to name yet. */
        const val LOOKUP_BINDING = "account-lookup"
    }
}
