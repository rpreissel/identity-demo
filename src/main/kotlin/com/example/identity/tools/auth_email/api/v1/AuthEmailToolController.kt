package com.example.identity.tools.auth_email.api.v1

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_email.internal.authemail.AuthEmailToolHandler
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
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
import com.example.identity.contract.tool_api.envelope.API_V1

private const val AUTH_EMAIL_TOOL_ID = "auth-email"

data class AuthEmailPatchRequest(@field:Schema(example = "123456") val code: String? = null)

/**
 * toolId=auth-email (device-linked case). One controller owns activation, PATCH and GET for
 * this tool (docs/08-projektrahmen.md A11) - no generic toolId dispatch anywhere.
 */
@RestController
@Tag(name = "Tool: E-Mail")
@SecurityRequirement(name = "dpop")
class AuthEmailToolController(
    private val handler: AuthEmailToolHandler,
    private val toolJourney: ToolJourney
) {

    @PostMapping("$API_V1/channels/{channelSessionId}/tools/auth-email")
    @Operation(
        summary = "Activate auth-email",
        description = "No request body: toolId already carries kind and method.",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "STEP_UP_IN_PROGRESS", "currentAcr": "loa1"},
                      "next": {"type": "tool", "toolId": "auth-email", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        @PathVariable channelSessionId: ChannelSessionId,
        @BindingKey bindingKeyRef: String,
        uriBuilder: UriComponentsBuilder
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.beginActivation(channelSessionId, bindingKeyRef, AUTH_EMAIL_TOOL_ID)

        // Only the accountId is resolved here, so the handler never sees a nullable parameter
        // (docs/03-tool-architektur.md #2); the confirmed address itself is the handler's own
        // lookup, same 422 either way.
        val accountId = context.accountId
            ?: throw UnresolvableReferenceException(Text("Kein Konto fuer diesen Kanal"))
        val outcome = handler.start(context.toolSessionId, accountId)

        val response = toolJourney.applyOutcome(context, outcome)
        val location = toolJourney.activationLocation(context, uriBuilder.build().toUri())
        return ResponseEntity.status(HttpStatus.CREATED).location(location).body(response)
    }

    @PatchMapping("$API_V1/tools/{toolSessionId}/auth-email")
    @Operation(
        summary = "Confirm the code sent to the account's confirmed email address",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["password", "email"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @PathVariable toolSessionId: ToolSessionId,
        @BindingKey bindingKeyRef: String,
        @RequestBody(required = false) request: AuthEmailPatchRequest?
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, AUTH_EMAIL_TOOL_ID)

        val body = request ?: AuthEmailPatchRequest()
        val outcome = handler.patch(toolSessionId, body.code, context.accountId)

        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    @GetMapping("$API_V1/tools/{toolSessionId}/auth-email")
    @Operation(
        summary = "Read the current auth-email state",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "STEP_UP_IN_PROGRESS", "currentAcr": "loa1"},
                      "next": {"type": "tool", "toolId": "auth-email", "step": "auth", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"}
                    }
                """)])]
            )
        ]
    )
    fun read(
        @PathVariable toolSessionId: ToolSessionId,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadContext(toolSessionId, bindingKeyRef, AUTH_EMAIL_TOOL_ID)
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
