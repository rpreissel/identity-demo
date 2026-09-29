package com.example.identity.tools.auth_invite.api.v1

import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.directory.normalizeKvnr
import com.example.identity.contract.tool_api.envelope.API_V1
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.tools.auth_invite.internal.AuthInviteToolHandler
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
import java.util.UUID

private const val AUTH_INVITE_TOOL_ID = "auth-invite"

data class AuthInvitePatchRequest(
    @field:Schema(example = "A123456789") val kvnr: String? = null,
    /** Only without a KVNR (a Partner, ADR-34). */
    @field:Schema(example = "P000000004") val partnernr: String? = null,
    /** The one-time password from the letter; separators and case do not count. */
    @field:Schema(example = "ABCD-EFGH-JKMN") val code: String? = null
)

/**
 * toolId=auth-invite (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). One controller owns
 * activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11).
 */
@RestController
@Tag(name = "Tool: Einmalkennwort", description = "KVNR or Partnernummer plus a one-time password for one process")
@SecurityRequirement(name = "dpop")
class AuthInviteToolController(
    private val handler: AuthInviteToolHandler,
    private val personDirectory: PersonDirectory,
    private val toolJourney: ToolJourney,
    private val lockouts: Lockouts
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/auth-invite")
    @Operation(
        summary = "Activate auth-invite",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-invite", "step": "input", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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
        val context = toolJourney.beginActivation(channelSessionId, bindingKeyRef, AUTH_INVITE_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolJourney.applyOutcome(context, outcome)
        val location = toolJourney.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/auth-invite")
    @Operation(
        summary = "Supply the number and the one-time password",
        description = "KVNR, or Partnernummer without a KVNR, together with the one-time password from the letter.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Accepted - the channel is signed in for the invitation's process.",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED"},
                      "next": {"type": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: AuthInvitePatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, AUTH_INVITE_TOOL_ID)
        val body = request ?: AuthInvitePatchRequest()
        // The KVNR comes first (ADR-34): given, it alone decides; the Partnernummer only counts without one.
        val personId = when {
            !body.kvnr.isNullOrBlank() -> personDirectory.findPersonIdByKvnr(normalizeKvnr(body.kvnr))
            !body.partnernr.isNullOrBlank() -> personDirectory.findPersonIdByPartnernr(body.partnernr)
            else -> null
        }
        // Folded into the ordinary failure, see Lockouts.isIdentLockedOut.
        val throttled = lockouts.isIdentLockedOut(personId)
        val outcome = handler.patch(toolSessionId, body.kvnr, body.partnernr, body.code, personId, throttled)
        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/auth-invite")
    @Operation(summary = "Read the current auth-invite state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadContext(toolSessionId, bindingKeyRef, AUTH_INVITE_TOOL_ID)
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
