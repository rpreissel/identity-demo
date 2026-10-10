package com.example.identity.tools.auth_invite.api.v1

import com.example.identity.tools.auth_invite.AUTH_INVITE_LOOKUP_TOOL_ID
import com.example.identity.contract.tool_api.envelope.NO_REQUEST_BODY
import com.example.identity.contract.tool_api.envelope.EXAMPLE_TOOL_SESSION_ID
import com.example.identity.contract.tool_api.envelope.EXAMPLE_CHANNEL_SESSION_ID
import com.example.identity.tools.auth_invite.AuthInviteLookup
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.readResponse
import com.example.identity.contract.tool_api.activated
import com.example.identity.contract.tool_api.applied
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.directory.normalizeKvnr
import com.example.identity.contract.tool_api.envelope.TOOLS_API
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.tools.auth_invite.internal.AuthInviteLookupToolHandler
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

data class AuthInvitePatchRequest(
    @field:Schema(example = "A123456789") val kvnr: String? = null,
    /** Only without a KVNR (a Partner, ADR-34). */
    @field:Schema(example = "P000000004") val partnerNumber: String? = null,
    /** The one-time password from the letter; separators and case do not count. */
    @field:Schema(example = "ABCD-EFGH-JKMN") val code: String? = null
)

/**
 * toolId=auth-invite-lookup (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). One controller owns
 * activation, PATCH and GET for this tool (docs/08-projektrahmen.md A11).
 */
@RestController
@Tag(name = "Tool: Einmalkennwort", description = "KVNR or Partnernummer plus a one-time password for one process")
@SecurityRequirement(name = "dpop")
class AuthInviteLookupToolController(
    private val handler: AuthInviteLookupToolHandler,
    private val personDirectory: PersonDirectory,
    private val toolJourney: ToolJourney,
    private val lockouts: Lockouts
) : ToolController {

    override val tool = AuthInviteLookup

    @PostMapping("$TOOLS_API/$AUTH_INVITE_LOOKUP_TOOL_ID/v1")
    @Operation(
        summary = "Activate auth-invite-lookup",
        description = NO_REQUEST_BODY,
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-invite-lookup", "step": "input", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"}
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

    @PatchMapping("$TOOLS_API/$AUTH_INVITE_LOOKUP_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Supply the number and the one-time password",
        description = "KVNR, or Partnernummer without a KVNR, together with the one-time password from the letter.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Accepted - the channel is signed in for the invitation's process.",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "AUTHENTICATED"},
                      "next": {"type": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: AuthInvitePatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val body = request ?: AuthInvitePatchRequest()
        // The KVNR comes first (ADR-34): given, it alone decides; the Partnernummer only counts without one.
        val personId = when {
            !body.kvnr.isNullOrBlank() -> personDirectory.findPersonIdByKvnr(normalizeKvnr(body.kvnr))
            !body.partnerNumber.isNullOrBlank() -> personDirectory.findPersonIdByPartnerNumber(body.partnerNumber)
            else -> null
        }
        // Folded into the ordinary failure, see Lockouts.isIdentLockedOut.
        val rateLimited = lockouts.isIdentLockedOut(personId)
        val outcome = handler.patch(context.toolSessionId, body.kvnr, body.partnerNumber, body.code, personId, rateLimited)
        return toolJourney.applied(context, outcome)
    }

    @GetMapping("$TOOLS_API/$AUTH_INVITE_LOOKUP_TOOL_ID/v1/{toolSessionId}")
    @Operation(summary = "Read the current auth-invite-lookup state")
    fun read(
        context: ToolContext
    ): ResponseEntity<ChannelResponse> {
        return toolJourney.readResponse(context) { handler.read(context.toolSessionId) }
    }
}
