package com.example.identity.tools.ident_eid.api.v1

import com.example.identity.tools.ident_eid.IDENT_EID_TOOL_ID
import com.example.identity.contract.tool_api.envelope.NO_REQUEST_BODY
import com.example.identity.contract.tool_api.envelope.EXAMPLE_TOOL_SESSION_ID
import com.example.identity.contract.tool_api.envelope.EXAMPLE_CHANNEL_SESSION_ID
import com.example.identity.tools.ident_eid.IdentEid
import com.example.identity.tools.ident_eid.internal.EidPatchFields
import com.example.identity.tools.ident_eid.internal.IdentEidToolHandler
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.readResponse
import com.example.identity.contract.tool_api.activated
import com.example.identity.contract.tool_api.applied
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

data class IdentEidPatchRequest(
    @field:Schema(example = "Muster") val familyName: String? = null,
    @field:Schema(example = "Max") val givenNames: String? = null,
    @field:Schema(example = "1985-03-12") val birthDate: LocalDate? = null,
    @field:Schema(description = "Straße und Hausnummer in einer Zeile, wie die Karte sie liefert", example = "Musterstraße 1")
    val streetAddress: String? = null,
    @field:Schema(example = "10117") val postalCode: String? = null,
    @field:Schema(example = "Berlin") val locality: String? = null,
    @field:Schema(example = "T0103005T4UY6CQ1B3LN0T28WJ") val restrictedId: String? = null,
    @field:Schema(example = "123456") val pin: String? = null
)

/**
 * toolId=ident-eid. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: eID", description = "Simulated eID card read, then PIN - attests what the card shows")
@SecurityRequirement(name = "dpop")
class IdentEidToolController(
    private val handler: IdentEidToolHandler,
    private val toolJourney: ToolJourney
) : ToolController {

    override val tool = IdentEid

    @PostMapping("$TOOLS_API/$IDENT_EID_TOOL_ID/v1")
    @Operation(
        summary = "Activate ident-eid",
        description = NO_REQUEST_BODY,
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "ident-eid", "step": "input", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"},
                      "stepData": {"kind": "missing-fields", "missingFields": ["familyName", "givenNames", "birthDate", "streetAddress", "postalCode", "locality", "restrictedId"]}
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

    @PatchMapping("$TOOLS_API/$IDENT_EID_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Supply the simulated card's Ausweisdaten, then the PIN",
        description = "Only the fields being supplied or corrected need to be sent; all of them together also completes in one call. " +
            "The card data is checked for format and completeness as soon as it is complete, and only then is the PIN asked for.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [
                    ExampleObject(name = "After card data - the PIN is missing", value = """
                        {
                          "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "ANONYMOUS"},
                          "next": {"type": "tool", "toolId": "ident-eid", "step": "input", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"},
                          "stepData": {"kind": "missing-fields", "missingFields": ["pin"]}
                        }
                    """),
                    ExampleObject(name = "After pin - attested, the assignment step follows", value = """
                        {
                          "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "ANONYMOUS"},
                          "next": {"type": "tool", "toolId": "ident-kvnr", "step": "input"}
                        }
                    """)
                ])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: IdentEidPatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val body = request ?: IdentEidPatchRequest()
        val fields = EidPatchFields(
            familyName = body.familyName,
            givenNames = body.givenNames,
            birthDate = body.birthDate,
            streetAddress = body.streetAddress,
            postalCode = body.postalCode,
            locality = body.locality,
            restrictedId = body.restrictedId,
            pin = body.pin
        )
        val outcome = handler.patch(context.toolSessionId, fields)

        return toolJourney.applied(context, outcome)
    }

    @GetMapping("$TOOLS_API/$IDENT_EID_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Read the current ident-eid state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "ident-eid", "step": "input", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"},
                      "stepData": {"kind": "missing-fields", "missingFields": ["pin"]}
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
