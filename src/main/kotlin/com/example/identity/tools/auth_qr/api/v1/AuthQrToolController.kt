package com.example.identity.tools.auth_qr.api.v1

import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_qr.AuthQrDescriptor
import com.example.identity.tools.auth_qr.internal.authqr.AuthQrToolHandler
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import io.swagger.v3.oas.annotations.Operation
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

private const val AUTH_QR_TOOL_ID = "auth-qr"

/**
 * toolId=auth-qr. One controller owns activation, PATCH and GET for this tool
 * (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: QR-Login")
@SecurityRequirement(name = "dpop")
class AuthQrToolController(
    private val handler: AuthQrToolHandler,
    private val descriptor: AuthQrDescriptor,
    private val accountDirectory: AccountDirectory,
    private val toolJourney: ToolJourney
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/auth-qr")
    @Operation(summary = "Activate auth-qr", description = "No request body: toolId already carries kind and method.")
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.beginActivation(channelSessionId, bindingKeyRef, AUTH_QR_TOOL_ID)
        val accountId = context.accountId
            ?.takeIf { accountDirectory.activeEnrollment(it, descriptor.method) != null }
            ?: throw UnresolvableReferenceException(Text("Kein aktives Anmeldeverfahren dieser Art fuer dieses Konto"), "no active qr-login method")
        val outcome = handler.start(context.toolSessionId, accountId)

        val response = toolJourney.applyOutcome(context, outcome)
        val location = toolJourney.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/auth-qr")
    @Operation(
        summary = "Poll for the APP side's decision, then submit the confirmation code the app shows",
        description = "Step waitForApp: an empty PATCH is the poll. Step enterCode: the app approved and shows a " +
            "confirmation code; the browser is logged in only once it submits that code (docs/05-api.md, " +
            "Peer-Login bestätigen; docs/07-betrieb.md #5)."
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: QrConfirmationCodeRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, AUTH_QR_TOOL_ID)
        val outcome = handler.patch(toolSessionId, request?.confirmationCode)
        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/auth-qr")
    @Operation(summary = "Read the current auth-qr state")
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadContext(toolSessionId, bindingKeyRef, AUTH_QR_TOOL_ID)
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
