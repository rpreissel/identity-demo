package com.example.identity.tools.auth_sms.api.v1

import com.example.identity.tools.auth_sms.AUTH_SMS_LOOKUP_TOOL_ID
import com.example.identity.tools.auth_sms.SmsModule
import com.example.identity.tools.auth_sms.internal.authsmslookup.AuthSmsLookupToolHandler
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ActivateTool
import com.example.identity.contract.tool_api.LoadTool
import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.readResponse
import com.example.identity.contract.tool_api.activated
import com.example.identity.contract.tool_api.directory.resolveAccountByEmail
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

data class AuthSmsLookupPatchRequest(
    @field:Schema(example = "max.mustermann@example.com") val email: String? = null,
    @field:Schema(example = "123456") val tan: String? = null
)

/**
 * toolId=auth-sms-lookup (docs/04-orchestrierung.md, lookup-based login). One controller owns
 * activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11).
 */
@RestController
@Tag(name = "Tool: SMS")
@SecurityRequirement(name = "dpop")
class AuthSmsLookupToolController(
    private val handler: AuthSmsLookupToolHandler,
    private val accountDirectory: AccountDirectory,
    private val toolJourney: ToolJourney,
    private val lockouts: Lockouts
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/$AUTH_SMS_LOOKUP_TOOL_ID")
    @Operation(
        summary = "Activate auth-sms-lookup",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-sms-lookup", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        @ActivateTool(AUTH_SMS_LOOKUP_TOOL_ID) context: AuthorizedToolContext,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.start(context.toolSessionId)
        return toolJourney.activated(context, outcome, uriBuilder)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/$AUTH_SMS_LOOKUP_TOOL_ID")
    @Operation(
        summary = "Supply email, then TAN",
        description = "First call with email resolves the account and triggers the TAN send; a second call with tan confirms it.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [
                    ExampleObject(name = "After email - TAN sent", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                          "next": {"type": "tool", "toolId": "auth-sms-lookup", "step": "tanInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                          "demo": {"tan": "123456"}
                        }
                    """),
                    ExampleObject(name = "After tan - logged in, device-binding offer", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa1", "currentAmr": ["sms"]},
                          "next": {"type": "orchestrator", "context": "authentication", "step": "offerDeviceBinding"}
                        }
                    """)
                ])]
            )
        ]
    )
    fun patch(
        @LoadTool(AUTH_SMS_LOOKUP_TOOL_ID) context: AuthorizedToolContext,
        @RequestBody(required = false) request: AuthSmsLookupPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val body = request ?: AuthSmsLookupPatchRequest()
        // email wins over a tan submitted in the same call: a (re-)submitted email restarts the
        // flow at a fresh TAN, so an old one has nothing left to be checked against.
        val outcome = if (body.email != null) {
            // Resolved here, since auth_sms may not depend on `account`. Unknown, without sms
            // method or locked all become null and look like a wrong TAN, so account existence
            // does not leak. The handler bounds the sends itself (SmsSendLimit).
            val resolved = accountDirectory.resolveAccountByEmail(body.email)
            val accountId = resolved?.takeUnless { lockouts.isLockedOut(it) }
            val enrollmentRef = accountId?.let { accountDirectory.activeEnrollment(it, SmsModule.method) }
            handler.submitEmail(context.toolSessionId, accountId, enrollmentRef)
        } else {
            handler.patch(context.toolSessionId, body.tan)
        }

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/$AUTH_SMS_LOOKUP_TOOL_ID")
    @Operation(
        summary = "Read the current auth-sms-lookup state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-sms-lookup", "step": "tanInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        @LoadTool(AUTH_SMS_LOOKUP_TOOL_ID) context: ToolContext
    ): ResponseEntity<ChannelResponse> {
        return toolJourney.readResponse(context) { handler.read(context.toolSessionId) }
    }
}
