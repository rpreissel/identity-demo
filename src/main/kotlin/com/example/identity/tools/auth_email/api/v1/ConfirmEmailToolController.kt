package com.example.identity.tools.auth_email.api.v1

import com.example.identity.tools.auth_email.CONFIRM_EMAIL_TOOL_ID
import com.example.identity.contract.tool_api.envelope.NO_REQUEST_BODY
import com.example.identity.contract.tool_api.envelope.EXAMPLE_TOOL_SESSION_ID
import com.example.identity.contract.tool_api.envelope.EXAMPLE_CHANNEL_SESSION_ID
import com.example.identity.tools.auth_email.ConfirmEmail
import com.example.identity.tools.auth_email.internal.confirmemail.ConfirmEmailToolHandler
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

data class ConfirmEmailPatchRequest(
    @field:Schema(example = "max.mustermann@example.com") val email: String? = null,
    @field:Schema(example = "123456") val code: String? = null
)

/**
 * toolId=confirm-email. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: E-Mail")
@SecurityRequirement(name = "dpop")
class ConfirmEmailToolController(
    private val handler: ConfirmEmailToolHandler,
    private val toolJourney: ToolJourney
) : ToolController {

    override val tool = ConfirmEmail

    @PostMapping("$TOOLS_API/$CONFIRM_EMAIL_TOOL_ID/v1")
    @Operation(
        summary = "Activate confirm-email",
        description = NO_REQUEST_BODY,
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "confirm-email", "step": "input", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        context: ActivationToolContext,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.start(context.toolSessionId)
        return toolJourney.activated(context, outcome, uriBuilder)
    }

    @PatchMapping("$TOOLS_API/$CONFIRM_EMAIL_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Supply email, then the confirmation code",
        description = "First call with email triggers the code send; a second call with code confirms it.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [
                    ExampleObject(name = "After email - code sent", value = """
                        {
                          "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "REGISTERING"},
                          "next": {"type": "tool", "toolId": "confirm-email", "step": "codeInput", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"},
                          "demo": {"tan": "123456"}
                        }
                    """),
                    ExampleObject(name = "After code - confirmed, chain continues", value = """
                        {
                          "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "REGISTERING"},
                          "next": {"type": "orchestrator", "context": "enrollment", "step": "selectMethod"},
                          "stepData": {"kind": "select-method", "options": ["enroll-password"]}
                        }
                    """)
                ])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: ConfirmEmailPatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val body = request ?: ConfirmEmailPatchRequest()
        val outcome = handler.patch(context.toolSessionId, body.email, body.code)

        return toolJourney.applied(context, outcome)
    }

    @GetMapping("$TOOLS_API/$CONFIRM_EMAIL_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Read the current confirm-email state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "confirm-email", "step": "codeInput", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"}
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
