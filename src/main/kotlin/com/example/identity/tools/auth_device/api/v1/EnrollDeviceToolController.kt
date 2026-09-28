package com.example.identity.tools.auth_device.api.v1

import com.example.identity.tools.auth_device.internal.enrolldevice.EnrollDeviceToolHandler
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.device.DeviceProofs
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
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

private const val ENROLL_DEVICE_TOOL_ID = "enroll-device"

data class DeviceProofPatchRequest(
    @field:Schema(example = "eyJhbGciOiJFUzI1NiIsInR5cCI6ImRwb3Arand0In0.eyJodG0iOiJQQVRDSCIsImh0dSI6Ii4uLiJ9.MEUCIQ")
    val deviceProof: String? = null,
    /** User-chosen display name for this device; never signed, no security relevance. */
    @field:Schema(example = "Laptop")
    val label: String? = null
)

/**
 * toolId=enroll-device (docs/03-tool-architektur.md): registers a device-bound key pair, gated by a
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
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/enroll-device")
    @Operation(
        summary = "Activate enroll-device",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa1", "currentAmr": ["password"]},
                      "next": {"type": "tool", "toolId": "enroll-device", "step": "enroll", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        @PathVariable channelSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.beginActivation(channelSessionId, bindingKeyRef, ENROLL_DEVICE_TOOL_ID)
        val outcome = handler.start(context.toolSessionId)
        val response = toolJourney.applyOutcome(context, outcome)
        val location = toolJourney.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/enroll-device")
    @Operation(
        summary = "Confirm device enrollment",
        description = "Body carries a self-signed device-proof JWT (typ=device-proof+jwt) over this exact URL, produced after the user confirms the mocked PIN/biometric prompt.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["password", "device"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: DeviceProofPatchRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, ENROLL_DEVICE_TOOL_ID)

        val proof = deviceProofs.validate(request?.deviceProof, httpRequest)
        val outcome = handler.patch(toolSessionId, proof.publicKey, proof.userVerification, bindingKeyRef, request?.label)

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/enroll-device")
    @Operation(
        summary = "Read the current enroll-device state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa1", "currentAmr": ["password"]},
                      "next": {"type": "tool", "toolId": "enroll-device", "step": "enroll", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        @PathVariable toolSessionId: UUID,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadContext(toolSessionId, bindingKeyRef, ENROLL_DEVICE_TOOL_ID)
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
