package com.example.identity.tools.auth_qr.api.v1

import com.example.identity.tools.auth_qr.AUTH_QR_TOOL_ID
import com.example.identity.tools.auth_qr.AuthQr
import com.example.identity.tools.auth_qr.QrModule
import com.example.identity.tools.auth_qr.internal.authqr.AuthQrToolHandler
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
import com.example.identity.contract.tool_api.envelope.API_V1

/**
 * toolId=auth-qr. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: QR-Login")
@SecurityRequirement(name = "dpop")
class AuthQrToolController(
    private val handler: AuthQrToolHandler,
    private val toolJourney: ToolJourney
) : ToolController {

    override val tool = AuthQr

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/$AUTH_QR_TOOL_ID")
    @Operation(
        summary = "Activate auth-qr",
        description = "No request body: toolId already carries kind and method.",
        responses = [ApiResponse(responseCode = "201", content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class))])]
    )
    fun activate(
        context: ActivationToolContext,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        // The opt-in holds no secret; it only has to exist for the account in hand.
        toolJourney.requireEnrollment(context, QrModule)
        val accountId = checkNotNull(context.accountId)
        val outcome = handler.start(context.toolSessionId, accountId)

        return toolJourney.activated(context, outcome, uriBuilder)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/$AUTH_QR_TOOL_ID")
    @Operation(
        summary = "Poll for the APP side's decision, then submit the confirmation code the app shows",
        description = "Step waitForApp: an empty PATCH is the poll. Step enterCode: the app approved and shows a " +
            "confirmation code; the browser is logged in only once it submits that code (docs/05-api.md, " +
            "Peer-Login bestätigen; docs/07-betrieb.md #5)."
    )
    fun patch(
        @RequestBody(required = false) request: QrConfirmationCodeRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.patch(context.toolSessionId, request?.confirmationCode)
        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/$AUTH_QR_TOOL_ID")
    @Operation(summary = "Read the current auth-qr state")
    fun read(
        context: ToolContext
    ): ResponseEntity<ChannelResponse> {
        return toolJourney.readResponse(context) { handler.read(context.toolSessionId) }
    }
}
