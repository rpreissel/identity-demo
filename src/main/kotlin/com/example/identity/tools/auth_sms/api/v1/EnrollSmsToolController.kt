package com.example.identity.tools.auth_sms.api.v1

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_sms.internal.enrollsms.EnrollSmsToolHandler
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import com.example.identity.contract.tool_api.envelope.API_V1

private const val ENROLL_SMS_TOOL_ID = "enroll-sms"

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
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/enroll-sms")
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
        @PathVariable channelSessionId: ChannelSessionId,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.beginActivation(channelSessionId, bindingKeyRef, ENROLL_SMS_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolJourney.applyOutcome(context, outcome)
        val location = toolJourney.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/enroll-sms")
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
        @PathVariable toolSessionId: ToolSessionId,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: EnrollSmsPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, ENROLL_SMS_TOOL_ID)

        val body = request ?: EnrollSmsPatchRequest()
        val outcome = handler.patch(toolSessionId, body.phoneNumber, body.tan)

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/enroll-sms")
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
        @PathVariable toolSessionId: ToolSessionId,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadContext(toolSessionId, bindingKeyRef, ENROLL_SMS_TOOL_ID)
        val outcome = if (toolJourney.isCurrentTool(context)) {
            checkNotNull(handler.read(toolSessionId) as? ToolOutcome.InProgress) {
                "read() must return InProgress while the tool is still current"
            }
        } else {
            null
        }
        return ResponseEntity.ok(toolJourney.buildReadResponse(context, outcome))
    }
}
