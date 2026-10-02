package com.example.identity.tools.ident_nect.api.v1

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.ident_nect.internal.IdentNectToolHandler
import com.example.identity.contract.tool_api.envelope.API_V1
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ToolOutcome
import com.fasterxml.jackson.annotation.JsonAlias
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

private const val IDENT_NECT_TOOL_ID = "ident-nect"

data class IdentNectActivateRequest(
    @field:Schema(
        description = "Where Nect sends the user back to; without it the app channel's /app/. " +
            "The web channel names Keycloak's action URL of the running step. Only an address under a configured prefix is accepted (400 otherwise)."
    )
    val returnUri: String? = null
)

data class IdentNectPatchRequest(
    // nectCaseId: the name Nect appends on the return; the web channel forwards the query as it is.
    @JsonAlias("nectCaseId")
    @field:Schema(description = "The case id Nect sent the user back with (?nectCaseId=...); also accepted as nectCaseId.")
    val caseId: UUID? = null,
    @field:Schema(description = "true opens a fresh Nect case instead of reporting one.")
    val retry: Boolean? = null,
    @field:Schema(
        description = "With retry: where Nect sends the user back to this time, checked like on activation. " +
            "The web channel names a fresh action URL, because Keycloak's action code is single-use; without it " +
            "the address from the activation stays."
    )
    val returnUri: String? = null
)

/**
 * toolId=ident-nect. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11). The client only ever carries the case id; the identification
 * result goes from Nect to the backend directly.
 */
@RestController
@Tag(name = "Tool: Nect", description = "Identification at Nect (eID, passport or EUDI wallet) via jump page and return")
@SecurityRequirement(name = "dpop")
class IdentNectToolController(
    private val handler: IdentNectToolHandler,
    private val toolJourney: ToolJourney
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/ident-nect")
    @Operation(
        summary = "Activate ident-nect",
        description = "Opens a Nect case; stepData carries the jump URL. The optional body names where Nect sends the user back to.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "ident-nect", "step": "redirect", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                      "stepData": {"kind": "nect-redirect", "jumpUrl": "/nect/?case=5b1c2d3e-0000-4000-8000-000000000001", "caseId": "5b1c2d3e-0000-4000-8000-000000000001"}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        @PathVariable channelSessionId: ChannelSessionId,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: IdentNectActivateRequest?,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.beginActivation(channelSessionId, bindingKeyRef, IDENT_NECT_TOOL_ID)
        val outcome = handler.start(context.toolSessionId, request?.returnUri?.takeIf { it.isNotBlank() })
        val response = toolJourney.applyOutcome(context, outcome)
        val location = toolJourney.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/ident-nect")
    @Operation(
        summary = "Report the returned Nect case, or open a new one",
        description = "The backend redeems the case's result from Nect itself - once, and only for the case this tool session opened.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(name = "Attested, the assignment question follows", value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "orchestrator", "context": "prompt", "step": "confirm"},
                      "stepData": {"kind": "confirm", "prompt": {"kind": "Confirm", "title": "Konto Ihrer Versichertennummer zuordnen?"}}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: ToolSessionId,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: IdentNectPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, IDENT_NECT_TOOL_ID)
        val body = request ?: IdentNectPatchRequest()
        val outcome = handler.patch(toolSessionId, body.caseId, body.retry == true, body.returnUri?.takeIf { it.isNotBlank() })
        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/ident-nect")
    @Operation(summary = "Read the current ident-nect state")
    fun read(
        @PathVariable toolSessionId: ToolSessionId,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadContext(toolSessionId, bindingKeyRef, IDENT_NECT_TOOL_ID)
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
