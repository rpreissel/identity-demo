package com.example.identity.tools.auth_password.api.v1

import com.example.identity.tools.auth_password.AuthPasswordLookupDescriptor
import com.example.identity.tools.auth_password.internal.authpasswordlookup.AuthPasswordLookupToolHandler
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.directory.resolveAccountByEmail
import com.example.identity.contract.tool_api.ToolOutcome
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

private const val AUTH_PASSWORD_LOOKUP_TOOL_ID = "auth-password-lookup"

data class AuthPasswordLookupPatchRequest(
    @field:Schema(example = "max.mustermann@example.com") val email: String? = null,
    @field:Schema(example = "Passwort!23") val password: String? = null
)

/**
 * toolId=auth-password-lookup (docs/04-orchestrierung.md, lookup-based login). One controller
 * owns activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11).
 */
@RestController
@Tag(name = "Tool: Passwort")
@SecurityRequirement(name = "dpop")
class AuthPasswordLookupToolController(
    private val handler: AuthPasswordLookupToolHandler,
    private val descriptor: AuthPasswordLookupDescriptor,
    private val accountDirectory: AccountDirectory,
    private val toolJourney: ToolJourney,
    private val lockouts: Lockouts
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/auth-password-lookup")
    @Operation(
        summary = "Activate auth-password-lookup",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "auth-password-lookup", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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
        val context = toolJourney.beginActivation(channelSessionId, bindingKeyRef, AUTH_PASSWORD_LOOKUP_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolJourney.applyOutcome(context, outcome)
        val location = toolJourney.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/auth-password-lookup")
    @Operation(
        summary = "Supply email and password together (self-verifying, single call)",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Correct email+password - logged in, offered the optional device-binding.",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa1", "currentAmr": ["password"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "offerDeviceBinding"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: AuthPasswordLookupPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, AUTH_PASSWORD_LOOKUP_TOOL_ID)

        val body = request ?: AuthPasswordLookupPatchRequest()
        // Resolved here, since auth_password may not depend on `account`. Null for an unknown
        // email or no active password method; the handler treats that like a wrong password.
        val resolved = body.email?.let { accountDirectory.resolveAccountByEmail(it) }
        // A throttled account becomes null like an unknown address. A 423 here would tell an
        // attacker which addresses have accounts.
        val accountId = resolved.takeUnless { lockouts.isLockedOut(it) }
        val enrollmentRef = accountId?.let { accountDirectory.activeEnrollment(it, descriptor.method) }
        val outcome = handler.patch(toolSessionId, body.email, body.password, accountId, enrollmentRef)

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/auth-password-lookup")
    @Operation(
        summary = "Read the current auth-password-lookup state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "tool", "toolId": "auth-password-lookup", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadContext(toolSessionId, bindingKeyRef, AUTH_PASSWORD_LOOKUP_TOOL_ID)
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
