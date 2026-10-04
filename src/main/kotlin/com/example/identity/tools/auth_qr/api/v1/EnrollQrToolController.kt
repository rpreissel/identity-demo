package com.example.identity.tools.auth_qr.api.v1

import com.example.identity.tools.auth_qr.ENROLL_QR_TOOL_ID
import com.example.identity.tools.auth_qr.EnrollQr
import com.example.identity.tools.auth_qr.internal.enrollqr.EnrollQrToolHandler
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.readResponse
import com.example.identity.contract.tool_api.activated
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import com.example.identity.contract.tool_api.envelope.TOOLS_API

/**
 * toolId=enroll-qr. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: QR-Login")
@SecurityRequirement(name = "dpop")
class EnrollQrToolController(
    private val handler: EnrollQrToolHandler,
    private val toolJourney: ToolJourney
) : ToolController {

    override val tool = EnrollQr

    @PostMapping("$TOOLS_API/$ENROLL_QR_TOOL_ID/v1")
    @Operation(
        summary = "Activate enroll-qr",
        description = "No request body: toolId already carries kind and method.",
        responses = [ApiResponse(responseCode = "201", content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class))])]
    )
    fun activate(
        context: ActivationToolContext,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.start(context.toolSessionId)
        return toolJourney.activated(context, outcome, uriBuilder)
    }

    @PatchMapping("$TOOLS_API/$ENROLL_QR_TOOL_ID/v1/{toolSessionId}")
    @Operation(summary = "Confirm the opt-in", description = "No request body - the call itself is the confirmation.")
    fun patch(
        context: AuthorizedToolContext
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.patch(context.toolSessionId)
        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$TOOLS_API/$ENROLL_QR_TOOL_ID/v1/{toolSessionId}")
    @Operation(summary = "Read the current enroll-qr state")
    fun read(
        context: ToolContext
    ): ResponseEntity<ChannelResponse> {
        return toolJourney.readResponse(context) { handler.read(context.toolSessionId) }
    }
}
