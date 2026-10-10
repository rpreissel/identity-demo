package com.example.identity.tools.auth_password.api.v1

import com.example.identity.tools.auth_password.ENROLL_PASSWORD_TOOL_ID
import com.example.identity.contract.tool_api.envelope.NO_REQUEST_BODY
import com.example.identity.contract.tool_api.envelope.EXAMPLE_TOOL_SESSION_ID
import com.example.identity.contract.tool_api.envelope.EXAMPLE_CHANNEL_SESSION_ID
import com.example.identity.tools.auth_password.EnrollPassword
import com.example.identity.tools.auth_password.PasswordModule
import com.example.identity.tools.auth_password.internal.enrollpassword.EnrollPasswordToolHandler
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.readResponse
import com.example.identity.contract.tool_api.activated
import com.example.identity.contract.tool_api.applied
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

data class EnrollPasswordPatchRequest(
    @field:Schema(example = "Passwort!23") val password: String? = null
)

/**
 * toolId=enroll-password. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: Passwort")
@SecurityRequirement(name = "dpop")
class EnrollPasswordToolController(
    private val handler: EnrollPasswordToolHandler,
    private val toolJourney: ToolJourney
) : ToolController {

    override val tool = EnrollPassword

    @PostMapping("$TOOLS_API/$ENROLL_PASSWORD_TOOL_ID/v1")
    @Operation(
        summary = "Activate enroll-password",
        description = NO_REQUEST_BODY,
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "enroll-password", "step": "enroll", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        context: ActivationToolContext,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.start(context.toolSessionId, replaces = toolJourney.findEnrollment(context, PasswordModule) != null)
        return toolJourney.activated(context, outcome, uriBuilder)
    }

    @PatchMapping("$TOOLS_API/$ENROLL_PASSWORD_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Supply the password",
        description = "The credential is self-verifying, no separate confirmation step.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["email", "password"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: EnrollPasswordPatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val body = request ?: EnrollPasswordPatchRequest()
        val outcome = handler.patch(context.toolSessionId, body.password)

        return toolJourney.applied(context, outcome)
    }

    @GetMapping("$TOOLS_API/$ENROLL_PASSWORD_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Read the current enroll-password state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "enroll-password", "step": "enroll", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        context: ToolContext
    ): ResponseEntity<ChannelResponse> {
        return toolJourney.readResponse(context) { handler.read(context.toolSessionId) }
    }
}
