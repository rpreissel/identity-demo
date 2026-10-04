package com.example.identity.tools.auth_qr.api.v1

import com.example.identity.tools.auth_qr.APPROVE_QR_TOOL_ID
import com.example.identity.tools.auth_qr.ApproveQr
import com.example.identity.tools.auth_qr.QrModule
import com.example.identity.tools.auth_qr.internal.confirmqrlogin.ConfirmQrLoginToolHandler
import com.example.identity.contract.tool_api.directory.AccountDirectory
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
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import com.example.identity.contract.tool_api.envelope.TOOLS_API

data class ConfirmQrLoginPatchRequest(
    @field:Schema(example = "ABCD-1234") val pairingCode: String? = null,
    @field:Schema(example = "accept") val decision: String? = null
)

data class ConfirmQrLoginActivateRequest(
    @field:Schema(example = "ABCD-1234") val pairingCode: String? = null
)

/**
 * toolId=approve-qr. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: QR-Login")
@SecurityRequirement(name = "dpop")
class ConfirmQrLoginToolController(
    private val handler: ConfirmQrLoginToolHandler,
    private val accountDirectory: AccountDirectory,
    private val toolJourney: ToolJourney
) : ToolController {

    override val tool = ApproveQr

    @PostMapping("$TOOLS_API/$APPROVE_QR_TOOL_ID/v1")
    @Operation(
        summary = "Activate approve-qr",
        description = "Optional body: {pairingCode}, when already known (e.g. from a demo-link deep link) - skips the input step.",
        responses = [ApiResponse(responseCode = "201", content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class))])]
    )
    fun activate(
        @RequestBody(required = false) request: ConfirmQrLoginActivateRequest?,
        context: ActivationToolContext,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.start(context.toolSessionId, request?.pairingCode)
        return toolJourney.activated(context, outcome, uriBuilder)
    }

    @PatchMapping("$TOOLS_API/$APPROVE_QR_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Supply the pairing code, then the accept/reject decision",
        description = "First call: {pairingCode}. Once resolved: {decision: accept|reject}."
    )
    fun patch(
        @RequestBody(required = false) request: ConfirmQrLoginPatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val body = request ?: ConfirmQrLoginPatchRequest()
        val accountId = checkNotNull(context.accountId) { "approve-qr on a channel without an accountId" }
        // Resolved here, since this module may not depend on `account` (docs/03-tool-architektur.md
        // #2). Without an active enroll-qr opt-in the account may not approve.
        val hasQrEnrollment = accountDirectory.activeEnrollment(accountId, QrModule.method) != null
        val outcome = handler.patch(context.toolSessionId, body.pairingCode, body.decision, accountId, hasQrEnrollment)

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$TOOLS_API/$APPROVE_QR_TOOL_ID/v1/{toolSessionId}")
    @Operation(summary = "Read the current approve-qr state")
    fun read(
        context: ToolContext
    ): ResponseEntity<ChannelResponse> {
        return toolJourney.readResponse(context) { handler.read(context.toolSessionId) }
    }
}
