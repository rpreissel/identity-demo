package com.example.identity.core.orchestrator.api.v1.keycloak

import com.example.identity.contract.tool_api.envelope.API_V1
import com.example.identity.core.orchestrator.channel.KeycloakChannelService
import com.example.identity.core.orchestrator.keycloak.LogoutTokenException
import com.example.identity.core.orchestrator.keycloak.LogoutTokenValidator
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * Keycloak's OIDC Back-Channel Logout: it calls this for every ended session one of our clients was
 * part of, whoever ended it (docs/05-api.md Abschnitt 3b). The signed `logout_token` is the only
 * credential; its `sid` names the session. Answers per section 2.8 of the specification.
 */
@RestController
@Tag(name = "Keycloak back-channel logout", description = "Keycloak reports an ended session")
class BackchannelLogoutController(
    private val logoutTokenValidator: LogoutTokenValidator,
    private val keycloakChannelService: KeycloakChannelService,
) {
    private val log = LoggerFactory.getLogger(BackchannelLogoutController::class.java)

    @PostMapping(BACKCHANNEL_LOGOUT_PATH, consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    @Operation(
        summary = "Keycloak ended one session",
        responses = [
            ApiResponse(responseCode = "200", description = "Recorded - no body."),
            ApiResponse(responseCode = "400", description = "The logout_token was refused (error=invalid_request)."),
        ]
    )
    fun backchannelLogout(
        @RequestParam("logout_token", required = false) logoutToken: String?,
        request: HttpServletRequest,
    ): ResponseEntity<Map<String, String>> {
        val token = try {
            // Only in the body (section 2.5): a token in the address lands in every proxy's access log.
            if (request.queryString?.contains("logout_token") == true) throw LogoutTokenException("logout_token in the query")
            logoutTokenValidator.validate(logoutToken)
        } catch (e: LogoutTokenException) {
            // The reason stays in the log: the caller learns only that the token was refused.
            log.warn("Back-channel logout refused: {}", e.message)
            return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(mapOf("error" to "invalid_request"))
        }
        keycloakChannelService.signedOutAtKeycloak(token.sid)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build()
    }

    companion object {
        /** Keycloak's clients name this address (keycloak-migrations V9). */
        const val BACKCHANNEL_LOGOUT_PATH = "$API_V1/kc/backchannel-logout"
    }
}
