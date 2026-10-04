package com.example.identity.tools.auth_email.api.v1

import com.example.identity.tools.auth_email.AUTH_EMAIL_LOOKUP_TOOL_ID
import com.example.identity.tools.auth_email.AuthEmailLookup
import com.example.identity.tools.auth_email.internal.authemaillookup.AuthEmailLookupToolHandler
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.directory.resolveAccountByEmail
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.Lockouts
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

data class AuthEmailLookupPatchRequest(
    @field:Schema(example = "max.mustermann@example.com") val email: String? = null,
    @field:Schema(example = "123456") val code: String? = null
)

/**
 * toolId=auth-email-lookup (docs/04-orchestrierung.md, lookup-based login). One controller owns
 * activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11).
 */
@RestController
@Tag(name = "Tool: E-Mail")
@SecurityRequirement(name = "dpop")
class AuthEmailLookupToolController(
    private val handler: AuthEmailLookupToolHandler,
    private val accountDirectory: AccountDirectory,
    private val toolJourney: ToolJourney,
    private val lockouts: Lockouts
) : ToolController {

    override val tool = AuthEmailLookup

    @PostMapping("$TOOLS_API/$AUTH_EMAIL_LOOKUP_TOOL_ID/v1")
    @Operation(
        summary = "Activate auth-email-lookup",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-email-lookup", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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

    @PatchMapping("$TOOLS_API/$AUTH_EMAIL_LOOKUP_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Supply email, then code",
        description = "First call with email resolves the account and triggers the confirmation code send; a second call with code confirms it.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [
                    ExampleObject(name = "After email - code sent", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                          "next": {"type": "tool", "toolId": "auth-email-lookup", "step": "codeInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                          "demo": {"tan": "123456"}
                        }
                    """),
                    ExampleObject(name = "After code - logged in, device-binding offer", value = """
                        {
                          "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa1", "currentAmr": ["email"]},
                          "next": {"type": "orchestrator", "context": "authentication", "step": "offerDeviceBinding"}
                        }
                    """)
                ])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: AuthEmailLookupPatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val body = request ?: AuthEmailLookupPatchRequest()
        // email wins over a code submitted in the same call: a (re-)submitted email restarts the
        // flow at a fresh code, so an old one has nothing left to be checked against.
        val outcome = if (body.email != null) {
            // Resolved only to ask the login lock. A locked account is passed as `locked`, not
            // raised, so the response looks like one for an unknown address. The handler bounds
            // the sends itself (EmailSendLimit).
            val resolvedAccountId = accountDirectory.resolveAccountByEmail(body.email)
            val locked = resolvedAccountId?.let { lockouts.isLockedOut(it) } ?: false
            handler.submitEmail(context.toolSessionId, body.email, locked)
        } else {
            handler.patch(context.toolSessionId, body.code)
        }

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$TOOLS_API/$AUTH_EMAIL_LOOKUP_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Read the current auth-email-lookup state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-email-lookup", "step": "codeInput", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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
