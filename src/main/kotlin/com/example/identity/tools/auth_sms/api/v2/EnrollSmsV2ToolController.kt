package com.example.identity.tools.auth_sms.api.v2

import com.example.identity.tools.auth_sms.ENROLL_SMS_TOOL_ID
import com.example.identity.tools.auth_sms.EnrollSms
import com.example.identity.tools.auth_sms.SmsModule
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
import com.example.identity.contract.tool_api.envelope.TOOLS_API

data class EnrollSmsV2PatchRequest(
    @field:Schema(example = "+49 170 1234567") val phoneNumber: String? = null,
    @field:Schema(
        description = "Consent to storing the number and sending codes to it. Required with the phone number; " +
            "without it no code is sent and missingFields names it.",
        example = "true",
    )
    val consent: Boolean? = null,
    @field:Schema(example = "123456") val tan: String? = null,
)

/**
 * toolId=enroll-sms in version 2 (ADR-51): like version 1, but the phone number needs the consent
 * that comes with it. One handler serves both versions; this controller only has its own contract.
 */
@RestController
@Tag(name = "Tool: SMS")
@SecurityRequirement(name = "dpop")
class EnrollSmsV2ToolController(
    private val handler: EnrollSmsToolHandler,
    private val toolJourney: ToolJourney
) : ToolController {

    override val tool = EnrollSms

    @PostMapping("$TOOLS_API/$ENROLL_SMS_TOOL_ID/v2")
    @Operation(
        summary = "Activate enroll-sms (version 2)",
        description = "No request body: toolId already carries kind and method. The first step asks for the number and the consent.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "enroll-sms", "step": "enroll", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                      "stepData": {"kind": "enroll-sms", "missingFields": ["phoneNumber", "consent"], "replaces": false}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        context: ActivationToolContext,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.start(context.toolSessionId, context.version, replaces = toolJourney.findEnrollment(context, SmsModule) != null)
        return toolJourney.activated(context, outcome, uriBuilder)
    }

    @PatchMapping("$TOOLS_API/$ENROLL_SMS_TOOL_ID/v2/{toolSessionId}")
    @Operation(
        summary = "Supply phone number with consent, then TAN",
        description = "First call with phoneNumber and consent=true triggers the TAN send; without consent nothing is " +
            "sent and missingFields names it. A second call with tan confirms the number.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [
                    ExampleObject(name = "phoneNumber without consent - nothing sent", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "tool", "toolId": "enroll-sms", "step": "enroll", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                          "stepData": {"kind": "enroll-sms", "missingFields": ["phoneNumber", "consent"], "replaces": false}
                        }
                    """),
                    ExampleObject(name = "After phoneNumber and consent - TAN sent", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                          "next": {"type": "tool", "toolId": "enroll-sms", "step": "tanInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                          "demo": {"tan": "123456"}
                        }
                    """)
                ])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: EnrollSmsV2PatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val body = request ?: EnrollSmsV2PatchRequest()
        val outcome = handler.patch(context.toolSessionId, context.version, body.phoneNumber, body.tan, body.consent, masterKeyId = context.masterKey())
        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$TOOLS_API/$ENROLL_SMS_TOOL_ID/v2/{toolSessionId}")
    @Operation(
        summary = "Read the current enroll-sms state (version 2)",
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
        return toolJourney.readResponse(context) { handler.read(context.toolSessionId, context.version) }
    }
}
