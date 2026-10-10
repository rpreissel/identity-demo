package com.example.identity.core.orchestrator.api.v1.tool

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.envelope.EXAMPLE_CHANNEL_SESSION_ID
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
import com.example.identity.contract.tool_api.envelope.TOOLS_API
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolVersion

/**
 * Leaves the activated tool: declines it ([abandon]) or goes back to the selection ([back]). Never
 * starts another tool; the client activates that itself. The only generic, toolId-keyed tool
 * endpoint; every other tool operation has its own controller in its method module (ADR-1). Its
 * form is the same in every tool and version, so it lives at the tool's own path (ADR-51). It
 * lives in the orchestrator, because what follows is decided by the journey's state, and
 * `tool_api` is a contract, not a web layer.
 */
@RestController
@RequestMapping("$TOOLS_API/{toolId}/v{version}/{toolSessionId}")
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
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "STEP_UP_IN_PROGRESS", "currentAcr": "loa1"},
                      "next": {"type": "orchestrator", "context": "auth", "step": "selectMethod"},
                      "stepData": {"kind": "select-method", "options": ["auth-password", "auth-device"]}
                    }
                """)])]
            )
        ]
    )
    fun abandon(
        @PathVariable toolSessionId: ToolSessionId,
        @PathVariable toolId: String,
        @PathVariable version: Int,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, ToolVersion(ToolId(toolId), version))
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
                      "channel": {"channelSessionId": "$EXAMPLE_CHANNEL_SESSION_ID", "state": "ANONYMOUS"},
                      "next": {"type": "orchestrator", "context": "registration", "step": "selectIdentificationMethod"},
                      "stepData": {"kind": "select-method", "options": ["ident-fsc", "ident-eid", "ident-nect"]}
                    }
                """)])]
            )
        ]
    )
    fun back(
        @PathVariable toolSessionId: ToolSessionId,
        @PathVariable toolId: String,
        @PathVariable version: Int,
        @BindingKey bindingKeyRef: String
    ): ResponseEntity<ChannelResponse> {
        val context = toolJourney.loadCurrent(toolSessionId, bindingKeyRef, ToolVersion(ToolId(toolId), version))
        return ResponseEntity.ok(toolJourney.back(context))
    }
}
