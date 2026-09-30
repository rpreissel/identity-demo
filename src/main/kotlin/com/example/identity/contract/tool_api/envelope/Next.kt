package com.example.identity.contract.tool_api.envelope

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

/**
 * A pure address for the client's next step, never mixed with content (ADR-6). [type] is `"tool"`
 * or `"orchestrator"` (a page under `/channels/...`, addressed by [context] and [step]).
 * [toolSessionId] is `null` until a tool session exists for this step.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Next(
    @field:Schema(example = "tool")
    val type: String,
    @field:Schema(example = "auth-sms")
    val toolId: String? = null,
    @field:Schema(example = "authentication")
    val context: String? = null,
    @field:Schema(example = "auth")
    val step: String,
    val toolSessionId: ToolSessionId? = null
) {
    companion object {
        /** Addresses a running tool: the client calls `/tools/{toolSessionId}/{toolId}` next. */
        fun tool(toolId: String, step: String, toolSessionId: ToolSessionId? = null) =
            Next(type = "tool", toolId = toolId, step = step, toolSessionId = toolSessionId)

        /** Addresses an orchestrator-served page (a selection or completion screen), which has no tool session yet. */
        fun orchestrator(context: String, step: String) = Next(type = "orchestrator", context = context, step = step)

        /** The `next` of every AUTHENTICATED channel with no journey pending (docs/05-api.md #2). */
        val AUTHENTICATED = orchestrator("authentication", "authenticated")
    }
}
