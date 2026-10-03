package com.example.identity.tools.auth_sms.api.v1

import com.example.identity.tools.auth_sms.ENROLL_SMS_TOOL_ID
import com.example.identity.tools.auth_sms.EnrollSms
import com.example.identity.tools.auth_sms.internal.enrollsms.EnrollSmsToolHandler
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

data class EnrollSmsPatchRequest(
    @field:Schema(example = "+49 170 1234567") val phoneNumber: String? = null,
    @field:Schema(example = "123456") val tan: String? = null
)

/**
 * toolId=enroll-sms (docs/06-ablaeufe.md #4). One controller owns activation, PATCH and GET
 * for this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: SMS")
@SecurityRequirement(name = "dpop")
class EnrollSmsToolController(
    private val handler: EnrollSmsToolHandler,
    private val toolJourney: ToolJourney
) : ToolController {

    override val tool = EnrollSms

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/$ENROLL_SMS_TOOL_ID")
    @Operation(
        summary = "Activate enroll-sms",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "enroll-sms", "step": "enroll", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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

    @PatchMapping("$API_V1/tools/{toolSessionId}/$ENROLL_SMS_TOOL_ID")
    @Operation(
        summary = "Supply phone number, then TAN",
        description = "First call with phoneNumber triggers the TAN send; a second call with tan confirms it.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [
                    ExampleObject(name = "After phoneNumber - TAN sent", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "tool", "toolId": "enroll-sms", "step": "tanInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                          "demo": {"tan": "123456"}
                        }
                    """),
                    ExampleObject(name = "After tan - enrolled, chain continues", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "orchestrator", "context": "enrollment", "step": "selectMethod"},
                          "stepData": {"kind": "select-method", "options": ["confirm-email"]}
                        }
                    """)
                ])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: EnrollSmsPatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val body = request ?: EnrollSmsPatchRequest()
        val outcome = handler.patch(context.toolSessionId, body.phoneNumber, body.tan)

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/$ENROLL_SMS_TOOL_ID")
    @Operation(
        summary = "Read the current enroll-sms state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "enroll-sms", "step": "tanInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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
