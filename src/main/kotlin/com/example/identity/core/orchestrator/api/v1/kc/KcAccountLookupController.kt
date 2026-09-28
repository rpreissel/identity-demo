package com.example.identity.core.orchestrator.api.v1.kc

import com.example.identity.core.orchestrator.kc.KcAccountView
import com.example.identity.core.orchestrator.kc.KcAccountViews
import com.example.identity.core.orchestrator.kc.PeerAuthValidationException
import com.example.identity.core.orchestrator.kc.PeerAuthValidator
import com.example.identity.contract.tool_api.envelope.API_V1
import com.example.identity.core.orchestrator.dpop.buildRequestUrl
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
 * `channel_anchor` names what is looked up: the account id for [byId], [LOOKUP_ANCHOR] for a search
 * by address. The answer is signed, because it decides which user Keycloak logs in.
 */
@RestController
@Tag(name = "KC account lookup", description = "Read-through account lookup for Keycloak's user federation")
@SecurityRequirement(name = "kc-peer-auth")
class KcAccountLookupController(
    private val peerAuthValidator: PeerAuthValidator,
    private val views: KcAccountViews,
) {

    @GetMapping("$API_V1/kc/accounts/{accountId}")
    @Operation(summary = "The account as Keycloak shows it, by account id")
    fun byId(
        @PathVariable accountId: Long,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<KcAccountView> {
        validatePeerAuth(authorization, httpRequest, expectedAnchor = accountId.toString())
        return views.byAccountId(accountId).toResponse()
    }

    @GetMapping("$API_V1/kc/accounts")
    @Operation(summary = "The account as Keycloak shows it, by exact email or username - never a list")
    fun search(
        @RequestParam(required = false) email: String?,
        @RequestParam(required = false) username: String?,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<KcAccountView> {
        validatePeerAuth(authorization, httpRequest, expectedAnchor = LOOKUP_ANCHOR)
        val view = when {
            email != null && username == null -> views.byEmail(email)
            username != null && email == null -> views.byUsername(username)
            else -> return ResponseEntity.badRequest().build()
        }
        return view.toResponse()
    }

    private fun KcAccountView?.toResponse(): ResponseEntity<KcAccountView> =
        this?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()

    private fun validatePeerAuth(authorization: String?, httpRequest: HttpServletRequest, expectedAnchor: String) {
        val token = authorization?.trim()?.let { if (it.startsWith("Bearer ", ignoreCase = true)) it.substring(7).trim() else it }
            ?: throw PeerAuthValidationException("Missing Authorization header")
        val assertion = peerAuthValidator.validate(token, httpRequest.method, buildRequestUrl(httpRequest))
        if (assertion.channelAnchor != expectedAnchor) {
            throw PeerAuthValidationException("Peer-auth channel_anchor does not match this lookup")
        }
    }

    companion object {
        /** The `channel_anchor` of a search by address - there is no account id to name yet. */
        const val LOOKUP_ANCHOR = "account-lookup"
    }
}
