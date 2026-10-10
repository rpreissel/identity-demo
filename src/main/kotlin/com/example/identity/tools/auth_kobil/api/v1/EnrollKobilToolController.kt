package com.example.identity.tools.auth_kobil.api.v1

import com.example.identity.tools.auth_kobil.ENROLL_KOBIL_TOOL_ID
import com.example.identity.tools.auth_kobil.EnrollKobil
import com.example.identity.tools.auth_kobil.internal.enrollkobil.EnrollKobilToolHandler
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.readResponse
import com.example.identity.contract.tool_api.activated
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import com.example.identity.contract.tool_api.envelope.TOOLS_API

data class EnrollKobilPatchRequest(
    /** The app confirming that the SDK's activation ran; the device identifier is asked of KOBIL, never of the client. */
    @field:Schema(example = "true")
    val activated: Boolean? = null,
    /**
     * Whether the user agreed to unlocking by biometrics; only then is a counterpart of the app's
     * secret kept. Required, since there is no default for a consent.
     */
    @field:Schema(example = "true")
    val biometricConsent: Boolean? = null,
    /** User-chosen display name for this credential - display metadata only, no security relevance. */
    @field:Schema(example = "Diensthandy")
    val label: String? = null,
)

/**
 * toolId=enroll-kobil: binds a smartphone through KOBIL and keeps the resulting device
 * identifier, the backend-held PIN and the app's unlock secret as one account credential. One
 * controller owns activation, PATCH and GET (docs/08-projektrahmen.md A11).
 */
@RestController
@Tag(name = "Tool: KOBIL")
@SecurityRequirement(name = "dpop")
class EnrollKobilToolController(
    private val handler: EnrollKobilToolHandler,
    private val toolJourney: ToolJourney,
) : ToolController {

    override val tool = EnrollKobil

    @PostMapping("$TOOLS_API/$ENROLL_KOBIL_TOOL_ID/v1")
    @Operation(
        summary = "Activate enroll-kobil",
        description = "Provisions the KOBIL user and returns everything the app's SDK needs for its " +
            "activation call - including the PIN, which this backend mints and keeps.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["fsc"]},
                      "next": {"type": "tool", "toolId": "enroll-kobil", "step": "activate", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                      "stepData": {"kind": "kobil-activation", "missingFields": ["activated", "biometricConsent"], "tenantId": "identity-demo", "kobilUserId": "kob-1a2b3c4d5e6f", "activationCode": "K7M2PQX9", "pin": "40318827", "unlockSecret": "xE1r..."}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        context: ActivationToolContext,
        uriBuilder: UriComponentsBuilder,
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.start(context.toolSessionId)
        return toolJourney.activated(context, outcome, uriBuilder)
    }

    @PatchMapping("$TOOLS_API/$ENROLL_KOBIL_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Confirm the KOBIL activation",
        description = "Records the credential once KOBIL reports a bound device for this user.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["fsc", "kobil", "biometric"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: EnrollKobilPatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.patch(
            context.toolSessionId,
            request?.activated,
            request?.biometricConsent,
            context.bindingKeyRef,
            request?.label,
            masterKey = context::masterKey,
        )
        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$TOOLS_API/$ENROLL_KOBIL_TOOL_ID/v1/{toolSessionId}")
    @Operation(summary = "Read the current enroll-kobil state")
    fun read(
        context: ToolContext,
    ): ResponseEntity<ChannelResponse> {
        return toolJourney.readResponse(context) { handler.read(context.toolSessionId) }
    }
}
