package com.example.identity.contract.tool_api

/**
 * The controller of one tool (ADR-1): points to the [Tool] its endpoints serve. Its [ToolContext]
 * parameters are resolved for this tool, so a path and the context loaded for it cannot name
 * different tools.
 */
interface ToolController {
    val tool: Tool
}
