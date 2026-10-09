package com.example.identity.core.orchestrator.api.v1.keycloak

import com.example.identity.core.orchestrator.keycloak.peerAuthBodySha256
import com.example.identity.core.orchestrator.keycloak.peerAuthTarget
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.core.orchestrator.channel.KeycloakChannelService
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.envelope.AuthSubjectType
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidationException
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidator
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import java.time.Instant
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import com.example.identity.contract.tool_api.envelope.API_V1

/**
 * The Keycloak facade's one facade-specific endpoint (docs/05-api.md Abschnitt 3b) - everything
 * afterwards runs over the same facade-neutral tool endpoints the App channel uses. Proof-of-caller
 * is a signed Keycloak peer-auth assertion in `Authorization`, never DPoP.
 */
@RestController
@RequestMapping("$API_V1/kc/channels")
@Tag(name = "Keycloak channels", description = "The Keycloak facade's one facade-specific endpoint - upsert channel + advance journey")
@SecurityRequirement(name = "kc-peer-auth")
class KeycloakChannelController(
    private val peerAuthValidator: PeerAuthValidator,
    private val keycloakChannelService: KeycloakChannelService
) {

    @PatchMapping("/{channelSessionId}")
    @Operation(
        summary = "Upsert a Web channel and advance its journey",
        description = "Upsert semantics (docs/05-api.md Abschnitt 3b): creates the channel " +
            "on first call under this Keycloak-chosen id, just resumes it on every later one - " +
            "idempotent by construction, no separate create-then-resume round trip. " +
            "accountId/targetAcr are only meaningful on step-up.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "First call for a fresh Keycloak flow run - offers every Keycloak-usable method.",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "orchestrator", "context": "auth", "step": "selectMethod"},
                      "stepData": {"kind": "select-method", "options": ["ident-fsc", "auth-password"]}
                    }
                """)])]
            )
        ]
    )
    fun upsertChannel(
        @PathVariable channelSessionId: ChannelSessionId,
        @RequestHeader("Authorization") authorization: String?,
        @RequestBody(required = false) request: KeycloakChannelUpsertRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val assertion = peerAuthValidator.validate(
            bearerToken(authorization),
            httpRequest.method,
            peerAuthTarget(httpRequest),
            peerAuthBodySha256(httpRequest)
        )
        val body = request ?: KeycloakChannelUpsertRequest()
        val response = keycloakChannelService.upsertChannel(
            channelSessionId = channelSessionId,
            assertion = assertion,
            subject = subjectOf(body),
            targetAcr = body.targetAcr,
            kcSessionId = body.kcSessionId,
            availableTools = body.availableTools,
            intent = body.intent
        )
        return ResponseEntity.ok(response)
    }

    /** The subject the request names, as the domain knows it. */
    private fun subjectOf(body: KeycloakChannelUpsertRequest): Subject? = body.subject?.let {
        when (it.type) {
            AuthSubjectType.ACCOUNT -> Subject.Account(
                AccountId(requireNotNull(it.id.toLongOrNull()) { "subject.id of an account is no number" })
            )
            AuthSubjectType.INVITATION -> Subject.Invitation(InvitationId(it.id))
        }
    }

    @PostMapping("/{channelSessionId}/flow-end")
    @Operation(
        summary = "Report that this channel's Keycloak flow run ended",
        description = "For the Authenticator's end-of-flow lifecycle hook only (docs/05-api.md Abschnitt 3b). " +
            "Records what this channel proved for Keycloak's durable session kcSessionId (ADR-59), so a later " +
            "flow run of the same session starts with it. kcSessionId is passed explicitly, not read off the " +
            "channel's own binding: the evidence must outlive this flow run's channel. sessionExpiresAt " +
            "(epoch seconds) is the latest end of that Keycloak session without further activity; the " +
            "channel's expiry and the recorded evidence end with it (docs/adr/ADR-043)."
    )
    @ApiResponse(responseCode = "204", description = "Recorded")
    fun flowEnded(
        @PathVariable channelSessionId: ChannelSessionId,
        @RequestParam kcSessionId: String,
        @RequestParam(required = false) sessionExpiresAt: Long?,
        @RequestHeader("Authorization") authorization: String?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<Void> {
        val assertion = peerAuthValidator.validate(
            bearerToken(authorization),
            httpRequest.method,
            peerAuthTarget(httpRequest),
            peerAuthBodySha256(httpRequest)
        )
        keycloakChannelService.flowEnded(channelSessionId, assertion, kcSessionId, sessionExpiresAt?.let(Instant::ofEpochSecond))
        return ResponseEntity.noContent().build()
    }

    private fun bearerToken(authorization: String?): String {
        val value = authorization?.trim()
            ?: throw PeerAuthValidationException("Missing Authorization header")
        return if (value.startsWith("Bearer ", ignoreCase = true)) value.substring(7).trim() else value
    }
}
