package com.example.identity.core.orchestrator.api.v1.tool

import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.ToolJourney

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID
import com.example.identity.contract.tool_api.envelope.API_V1

/**
 * Leaves the activated tool: declines it ([abandon]) or goes back to the selection ([back]). Never
 * starts another tool; the client activates that itself. The only generic, toolId-keyed tool
 * endpoint; every other tool operation has its own controller in its method module (ADR-1). It
 * lives in the orchestrator, because what follows is decided by the journey's state, and
 * `tool_api` is a contract, not a web layer.
 */
@RestController
@RequestMapping("$API_V1/tools/{toolSessionId}/{toolId}")
@Tag(name = "Tools", description = "Leaving an activated tool: back to the selection, or declining it")
@SecurityRequirement(name = "dpop")
class LeaveToolController(private val toolJourney: ToolJourney) {

    @DeleteMapping
    @Operation(
        summary = "Abandon this tool attempt",
        description = "Moves the journey on according to the state it is standing on - to the next fallback option, " +
            "back to the selection step, or to the end of the journey if nothing else could be offered.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "auth-sms abandoned during a fallback chain - offers the other loa2 candidates.",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "STEP_UP_IN_PROGRESS", "currentAcr": "loa1"},
                      "next": {"type": "orchestrator", "context": "auth", "step": "selectMethod"},
                      "stepData": {"kind": "select-method", "options": ["auth-password", "auth-device"]}
                    }
                """)])]
            )
        ]
    )
    fun abandon(
        @PathVariable toolSessionId: UUID,
        @PathVariable toolId: String,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, toolId)
        return ResponseEntity.ok(toolJourney.abandon(context))
    }

    @PostMapping("back")
    @Operation(
        summary = "Go back from this tool to the selection",
        description = "Ends this tool attempt without declining it: the journey shows its selection page again, " +
            "with every method still on offer - this one included, and even when it is the only one. " +
            "Where the journey has no selection page (a single preferred method), this is the same as abandoning.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "Back from ident-fsc during registration - both identification methods are offered again.",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ChannelResponse::class), examples = [ExampleObject(value = """
                    {
                      "channel": {"channelSessionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6", "state": "REGISTERING"},
                      "next": {"type": "orchestrator", "context": "registration", "step": "selectIdentificationMethod"},
                      "stepData": {"kind": "select-method", "options": ["ident-fsc", "ident-eid", "ident-nect"]}
                    }
                """)])]
            )
        ]
    )
    fun back(
        @PathVariable toolSessionId: UUID,
        @PathVariable toolId: String,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, toolId)
        return ResponseEntity.ok(toolJourney.back(context))
    }
}
