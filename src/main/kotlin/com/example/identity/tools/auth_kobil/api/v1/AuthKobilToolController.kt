package com.example.identity.tools.auth_kobil.api.v1

import com.example.identity.tools.auth_kobil.AUTH_KOBIL_TOOL_ID
import com.example.identity.tools.auth_kobil.AuthKobil
import com.example.identity.tools.auth_kobil.KobilModule
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.tools.auth_kobil.internal.authkobil.AuthKobilToolHandler
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.credentials.PasswordCredentialPort
import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.readResponse
import com.example.identity.contract.tool_api.activated
import com.example.identity.contract.tool_api.ToolOutcome
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder
import com.example.identity.contract.tool_api.envelope.TOOLS_API

/** Body of `POST .../auth-kobil/pin-releases`. */
data class KobilPinReleaseRequest(val unlock: KobilUnlockCredential)

data class AuthKobilPatchRequest(
    /** The one-time password KOBIL handed the app - a reference to an assertion, not the assertion. */
    @field:Schema(example = "48210937")
    val otp: String? = null,
)

/**
 * toolId=auth-kobil: two acts, two URLs. Releasing the PIN is a single-use, non-idempotent creation,
 * and its response is the only place the PIN appears. In the PATCH it would end up in step state
 * that every GET rebuilds. A tool may own sub-resources (docs/05-api.md).
 */
@RestController
@Tag(name = "Tool: KOBIL")
@SecurityRequirement(name = "dpop")
class AuthKobilToolController(
    private val handler: AuthKobilToolHandler,
    private val toolJourney: ToolJourney,
    private val accountDirectory: AccountDirectory,
) : ToolController {

    override val tool = AuthKobil

    @PostMapping("$TOOLS_API/$AUTH_KOBIL_TOOL_ID/v1")
    @Operation(
        summary = "Activate auth-kobil",
        responses = [
            ApiResponse(
                responseCode = "201",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-kobil", "step": "unlock", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                      "stepData": {"kind": "kobil-unlock", "unlockOptions": ["biometric", "password"], "tenantId": "identity-demo", "kobilUserId": "kob-1a2b3c4d5e6f"}
                    }
                """)])]
            )
        ]
    )
    fun activate(
        context: ActivationToolContext,
        uriBuilder: UriComponentsBuilder,
    ): ResponseEntity<ChannelResponse> {
        val enrollmentRef = toolJourney.requireEnrollment(context, KobilModule)
        val outcome = handler.start(context.toolSessionId, enrollmentRef, passwordAvailable(context.accountId))
        return toolJourney.activated(context, outcome, uriBuilder)
    }

    @PostMapping("$TOOLS_API/$AUTH_KOBIL_TOOL_ID/v1/{toolSessionId}/pin-releases")
    @Operation(
        summary = "Release the backend-held PIN",
        description = "The app presents either the locally stored unlock secret (guarded by its " +
            "biometric prompt) or the account password. The PIN is in this response and nowhere " +
            "else - a later GET will not return it again.",
        responses = [
            ApiResponse(
                responseCode = "201",
                description = "Released",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "ANONYMOUS"},
                      "next": {"type": "tool", "toolId": "auth-kobil", "step": "otp", "toolSessionId": "9c858901-8a57-4791-81fe-4c455b099bc9"},
                      "stepData": {"kind": "kobil-otp", "missingFields": ["otp"], "tenantId": "identity-demo", "kobilUserId": "kob-1a2b3c4d5e6f", "kobilPin": "40318827"}
                    }
                """)])]
            ),
            ApiResponse(
                responseCode = "200",
                description = "Not released - an ordinary retryable failure, deliberately not an error status"
            )
        ]
    )
    fun releasePin(
        @RequestBody request: KobilPinReleaseRequest,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        // Resolved even when the account has no password: PasswordCredentialPort.verify must run
        // either way so a missing credential costs exactly what a wrong one does.
        val passwordEnrollment = passwordEnrollmentOf(context.accountId)

        val outcome = handler.releasePin(context.toolSessionId, request.unlock, passwordEnrollment)
        val response = toolJourney.applyOutcome(context, outcome)
        val status = if (outcome is ToolOutcome.Failed) HttpStatus.OK else HttpStatus.CREATED
        return ResponseEntity.status(status).body(response)
    }

    @PatchMapping("$TOOLS_API/$AUTH_KOBIL_TOOL_ID/v1/{toolSessionId}")
    @Operation(
        summary = "Redeem the one-time password",
        description = "The backend fetches the assertion behind the OTP from KOBIL, compares the " +
            "device identifier with the enrolled one and checks the reported risks.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "AUTHENTICATED", "currentAcr": "loa2", "currentAmr": ["kobil", "biometric"]},
                      "next": {"type": "orchestrator", "context": "authentication", "step": "authenticated"}
                    }
                """)])]
            )
        ]
    )
    fun patch(
        @RequestBody(required = false) request: AuthKobilPatchRequest?,
        context: AuthorizedToolContext,
    ): ResponseEntity<ChannelResponse> {
        val outcome = handler.patch(context.toolSessionId, request?.otp)
        return ResponseEntity.ok(toolJourney.applyOutcome(context, outcome))
    }

    /**
     * The account's active password credential, or null. Resolved here, since the handler cannot
     * see the account's other methods.
     */
    private fun passwordEnrollmentOf(accountId: AccountId?) =
        accountId?.let { accountDirectory.activeEnrollment(it, PasswordCredentialPort.METHOD) }

    private fun passwordAvailable(accountId: AccountId?) = passwordEnrollmentOf(accountId) != null

    @GetMapping("$TOOLS_API/$AUTH_KOBIL_TOOL_ID/v1/{toolSessionId}")
    @Operation(summary = "Read the current auth-kobil state")
    fun read(
        context: ToolContext,
    ): ResponseEntity<ChannelResponse> {
        return toolJourney.readResponse(context) { handler.read(context.toolSessionId, passwordAvailable(context.accountId)) }
    }
}
