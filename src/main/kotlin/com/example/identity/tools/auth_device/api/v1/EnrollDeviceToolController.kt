package com.example.identity.tools.auth_device.api.v1

import com.example.identity.tools.auth_device.ENROLL_DEVICE_TOOL_ID
import com.example.identity.contract.tool_api.envelope.NO_REQUEST_BODY
import com.example.identity.contract.tool_api.envelope.EXAMPLE_TOOL_SESSION_ID
import com.example.identity.contract.tool_api.envelope.EXAMPLE_CHANNEL_SESSION_ID
import com.example.identity.tools.auth_device.EnrollDevice
import com.example.identity.tools.auth_device.internal.enrolldevice.EnrollDeviceToolHandler
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.device.DeviceProofs
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
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import com.example.identity.contract.tool_api.envelope.TOOLS_API

data class DeviceProofPatchRequest(
    @field:Schema(example = "eyJhbGciOiJFUzI1NiIsInR5cCI6ImRwb3Arand0In0.eyJodG0iOiJQQVRDSCIsImh0dSI6Ii4uLiJ9.MEUCIQ")
    val deviceProof: String? = null,
    /** User-chosen display name for this device; never signed, no security relevance. */
    @field:Schema(example = "Laptop")
    val label: String? = null
)

/**
 * toolId=enroll-device (docs/verfahren/device.md): registers a device-bound key pair, gated by a
 * system PIN/biometric prompt, as a loa2-capable credential. One controller owns activation, PATCH
 * and GET for this tool (docs/08-projektrahmen.md A11).
 */
@RestController
@Tag(name = "Tool: Gerät")
@SecurityRequirement(name = "dpop")
class EnrollDeviceToolController(
    private val deviceProofs: DeviceProofs,
    private val handler: EnrollDeviceToolHandler,
    private val toolJourney: ToolJourney
) : ToolController {

    override val tool = EnrollDevice

    @PostMapping("$TOOLS_API/$ENROLL_DEVICE_TOOL_ID/v1")
    @Operation(
        summary = "Activate enroll-device",
        description = NO_REQUEST_BODY,
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "AUTHENTICATED", "currentAcr": "loa1", "currentAmr": ["password"]},
                      "next": {"type": "tool", "toolId": "enroll-device", "step": "enroll", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"}
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

    @PatchMapping("$TOOLS_API/$ENROLL_DEVICE_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Confirm device enrollment",
        description = "Body carries a self-signed device-proof JWT (typ=device-proof+jwt) over this exact URL, produced after the user confirms the mocked PIN/biometric prompt.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["password", "device"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: DeviceProofPatchRequest?,
        context: AuthorizedToolContext,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val proof = deviceProofs.validate(request?.deviceProof, httpRequest)
        val outcome = handler.patch(context.toolSessionId, proof.publicKey, proof.userVerification, context.bindingKeyRef, request?.label)

        return toolJourney.applied(context, outcome)
    }

    @GetMapping("$TOOLS_API/$ENROLL_DEVICE_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Read the current enroll-device state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "AUTHENTICATED", "currentAcr": "loa1", "currentAmr": ["password"]},
                      "next": {"type": "tool", "toolId": "enroll-device", "step": "enroll", "toolSessionId": "$EXAMPLE_TOOL_SESSION_ID"}
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
