package com.example.identity.tools.ident_fsc.api.v1

import com.example.identity.tools.ident_fsc.IDENT_FSC_TOOL_ID
import com.example.identity.tools.ident_fsc.IdentFsc
import com.example.identity.tools.ident_fsc.internal.IdentFscToolHandler
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.readResponse
import com.example.identity.contract.tool_api.activated
import com.example.identity.contract.tool_api.directory.normalizeKvnr
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import com.example.identity.contract.tool_api.envelope.TOOLS_API

data class IdentFscPatchRequest(
    @field:Schema(example = "A123456789") val kvnr: String? = null,
    /** Only without a KVNR (a Partner, ADR-34) - the client asks for the KVNR first, then for this. */
    @field:Schema(example = "P000000004") val partnerNumber: String? = null,
    @field:Schema(example = "Muster") val familyName: String? = null,
    @field:Schema(example = "Max") val givenNames: String? = null,
    @field:Schema(example = "1985-06-15") val birthDate: LocalDate? = null,
    @field:Schema(example = "VALIDCODE") val fsc: String? = null
)

/**
 * toolId=ident-fsc (docs/06-ablaeufe.md #2). One controller owns activation, PATCH and GET for
 * this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: Freischaltcode", description = "KVNR/name/givenNames/birthDate/FSC identification")
@SecurityRequirement(name = "dpop")
class IdentFscToolController(
    private val handler: IdentFscToolHandler,
    private val personDirectory: PersonDirectory,
    private val toolJourney: ToolJourney,
    private val lockouts: Lockouts
) : ToolController {

    override val tool = IdentFsc

    @PostMapping("$TOOLS_API/$IDENT_FSC_TOOL_ID/v1")
    @Operation(
        summary = "Activate ident-fsc",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "ident-fsc", "step": "input", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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

    @PatchMapping("$TOOLS_API/$IDENT_FSC_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Supply KVNR/name/givenNames/birthDate/FSC",
        description = "Only the fields being supplied or corrected need to be sent; all five together also resolves in one call.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Identified - the journey now chains toward the required loa2 (2nd factor + confirmed email).",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "orchestrator", "context": "enrollment", "step": "selectMethod"},
                      "stepData": {"kind": "select-method", "options": ["enroll-sms", "enroll-device"]}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: IdentFscPatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val body = request ?: IdentFscPatchRequest()
        // The KVNR comes first (ADR-34): given, it alone decides; the Partnernummer only counts without one.
        val personId = when {
            !body.kvnr.isNullOrBlank() -> personDirectory.findPersonIdByKvnr(normalizeKvnr(body.kvnr))
            !body.partnerNumber.isNullOrBlank() -> personDirectory.findPersonIdByPartnerNumber(body.partnerNumber)
            else -> null
        }
        // Folded into the handler's ordinary failure rather than raised - see
        // Lockouts.isIdentLockedOut: a distinguishable lock would leak which KVNRs exist.
        val rateLimited = lockouts.isIdentLockedOut(personId)
        val outcome = handler.patch(context.toolSessionId, body.kvnr, body.partnerNumber, body.familyName, body.givenNames, body.birthDate, body.fsc, personId, rateLimited)

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$TOOLS_API/$IDENT_FSC_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Read the current ident-fsc state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "ident-fsc", "step": "input", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
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
