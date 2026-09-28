package com.example.identity.tools.auth_sms.api.v1

import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_sms.AuthSmsDescriptor
import com.example.identity.tools.auth_sms.internal.authsms.AuthSmsToolHandler
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.util.UUID
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

private const val AUTH_SMS_TOOL_ID = "auth-sms"

data class AuthSmsPatchRequest(@field:Schema(example = "123456") val tan: String? = null)

/**
 * toolId=auth-sms (docs/06-ablaeufe.md #3). One controller owns activation, PATCH and GET for
 * this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: SMS")
@SecurityRequirement(name = "dpop")
class AuthSmsToolController(
    private val handler: AuthSmsToolHandler,
    private val descriptor: AuthSmsDescriptor,
    private val accountDirectory: AccountDirectory,
    private val toolJourney: ToolJourney
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/auth-sms")
    @Operation(
        summary = "Activate auth-sms",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "STEP_UP_IN_PROGRESS", "currentAcr": "loa1"},
                      "next": {"type": "tool", "toolId": "auth-sms", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.beginActivation(channelSessionId, bindingKeyRef, AUTH_SMS_TOOL_ID)

        // Resolved here, since the handler may not reference `account` (docs/06-ablaeufe.md #3).
        val enrollmentRef = context.accountId?.let { accountDirectory.activeEnrollment(it, descriptor.method) }
            ?: throw UnresolvableReferenceException(Text("Kein aktives Anmeldeverfahren dieser Art fuer dieses Konto"), "no active sms method")
        val outcome = handler.start(context.toolSessionId, enrollmentRef)

        val response = toolJourney.applyOutcome(context, outcome)
        val location = toolJourney.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/auth-sms")
    @Operation(
        summary = "Confirm the TAN sent to the account's enrolled phone number",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Correct TAN - the step-up is satisfied, channel settles into authenticated.",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["password", "sms"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: AuthSmsPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, AUTH_SMS_TOOL_ID)

        val body = request ?: AuthSmsPatchRequest()
        val outcome = handler.patch(toolSessionId, body.tan)

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/auth-sms")
    @Operation(
        summary = "Read the current auth-sms state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "STEP_UP_IN_PROGRESS", "currentAcr": "loa1"},
                      "next": {"type": "tool", "toolId": "auth-sms", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadContext(toolSessionId, bindingKeyRef, AUTH_SMS_TOOL_ID)
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
