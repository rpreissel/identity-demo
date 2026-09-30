package com.example.identity.tools.auth_qr.api.v1

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_qr.ConfirmQrLoginDescriptor
import com.example.identity.tools.auth_qr.internal.confirmqrlogin.ConfirmQrLoginToolHandler
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
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
import com.example.identity.contract.tool_api.envelope.API_V1

private const val CONFIRM_QR_LOGIN_TOOL_ID = "confirm-qr-login"

data class ConfirmQrLoginPatchRequest(
    @field:Schema(example = "ABCD-1234") val pairingCode: String? = null,
    @field:Schema(example = "accept") val decision: String? = null
)

data class ConfirmQrLoginActivateRequest(
    @field:Schema(example = "ABCD-1234") val pairingCode: String? = null
)

/**
 * toolId=confirm-qr-login. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: QR-Login")
@SecurityRequirement(name = "dpop")
class ConfirmQrLoginToolController(
    private val handler: ConfirmQrLoginToolHandler,
    private val descriptor: ConfirmQrLoginDescriptor,
    private val accountDirectory: AccountDirectory,
    private val toolJourney: ToolJourney
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/confirm-qr-login")
    @Operation(
        summary = "Activate confirm-qr-login",
        description = "Optional body: {pairingCode}, when already known (e.g. from a demo-link deep link) - skips the input step."
    )
    fun activate(
        @PathVariable channelSessionId: ChannelSessionId,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: ConfirmQrLoginActivateRequest?,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.beginActivation(channelSessionId, bindingKeyRef, CONFIRM_QR_LOGIN_TOOL_ID)
        val outcome = handler.start(context.toolSessionId, request?.pairingCode)
        val response = toolJourney.applyOutcome(context, outcome)
        val location = toolJourney.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/confirm-qr-login")
    @Operation(
        summary = "Supply the pairing code, then the accept/reject decision",
        description = "First call: {pairingCode}. Once resolved: {decision: accept|reject}."
    )
    fun patch(
        @PathVariable toolSessionId: ToolSessionId,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: ConfirmQrLoginPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, CONFIRM_QR_LOGIN_TOOL_ID)

        val body = request ?: ConfirmQrLoginPatchRequest()
        val accountId = checkNotNull(context.accountId) { "confirm-qr-login on a channel without an accountId" }
        // Resolved here, since this module may not depend on `account` (docs/03-tool-architektur.md
        // #2). Without an active enroll-qr opt-in the account may not approve.
        val hasQrEnrollment = accountDirectory.activeEnrollment(accountId, descriptor.method) != null
        val outcome = handler.patch(toolSessionId, body.pairingCode, body.decision, accountId, hasQrEnrollment)

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/confirm-qr-login")
    @Operation(summary = "Read the current confirm-qr-login state")
    fun read(
        @PathVariable toolSessionId: ToolSessionId,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadContext(toolSessionId, bindingKeyRef, CONFIRM_QR_LOGIN_TOOL_ID)
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
