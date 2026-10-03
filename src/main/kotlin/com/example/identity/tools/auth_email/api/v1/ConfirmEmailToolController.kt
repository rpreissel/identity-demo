package com.example.identity.tools.auth_email.api.v1

import com.example.identity.tools.auth_email.CONFIRM_EMAIL_TOOL_ID
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
import com.example.identity.contract.tool_api.envelope.API_V1

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

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/$CONFIRM_EMAIL_TOOL_ID")
    @Operation(
        summary = "Activate confirm-email",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "confirm-email", "step": "input", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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

    @PatchMapping("$API_V1/tools/{toolSessionId}/$CONFIRM_EMAIL_TOOL_ID")
    @Operation(
        summary = "Supply email, then the confirmation code",
        description = "First call with email triggers the code send; a second call with code confirms it.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [
                    ExampleObject(name = "After email - code sent", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "tool", "toolId": "confirm-email", "step": "codeInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                          "demo": {"tan": "123456"}
                        }
                    """),
                    ExampleObject(name = "After code - confirmed, chain continues", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
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

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/$CONFIRM_EMAIL_TOOL_ID")
    @Operation(
        summary = "Read the current confirm-email state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "confirm-email", "step": "codeInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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
