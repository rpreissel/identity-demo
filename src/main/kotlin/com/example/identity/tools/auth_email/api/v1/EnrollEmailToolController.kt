package com.example.identity.tools.auth_email.api.v1

import com.example.identity.tools.auth_email.ENROLL_EMAIL_TOOL_ID
import com.example.identity.tools.auth_email.EnrollEmail
import com.example.identity.tools.auth_email.internal.enrollemail.EnrollEmailToolHandler
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.activated
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import com.example.identity.contract.tool_api.envelope.API_V1

/**
 * toolId=enroll-email. One controller per tool (docs/08-projektrahmen.md A11), but this one has no
 * PATCH: activating the tool completes it, because the address was already proven by
 * `confirm-email` and there is nothing for the client to submit.
 */
@RestController
@Tag(name = "Tool: E-Mail-Login", description = "Activates the account's confirmed address as an authentication method")
@SecurityRequirement(name = "dpop")
class EnrollEmailToolController(
    private val handler: EnrollEmailToolHandler,
    private val toolJourney: ToolJourney
) : ToolController {

    override val tool = EnrollEmail

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/$ENROLL_EMAIL_TOOL_ID")
    @Operation(
        summary = "Activate enroll-email",
        description = "One shot: no request body, and the response already carries the completed outcome.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED"},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "done"}
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

    @GetMapping("$API_V1/tools/{toolSessionId}/$ENROLL_EMAIL_TOOL_ID")
    @Operation(summary = "Read the current enroll-email state")
    fun read(
        context: ToolContext
    ): ResponseEntity<ChannelResponse> {
        // Never InProgress - this tool has a single, already-completed state, so the read path
        // only ever reports what the journey has moved on to.
        return ResponseEntity.ok(toolJourney.buildReadResponse(context, null))
    }
}
