package com.example.identity.core.orchestrator.tool

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.ToolCatalog
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolModule
import org.springframework.stereotype.Component

/**
 * The tool catalog: every [ToolModule] the application context holds (docs/03-tool-architektur.md
 * #1). Purely a catalog: each tool's controller calls its handler directly (ADR-1), so there is no
 * toolId-to-handler dispatch. What a module declares is checked when it is built; here only what
 * spans modules.
 */
@Component
class ToolHandlerRegistry(modules: List<ToolModule>) : ToolCatalog {
    private val modules: List<ToolModule> = modules.toList()
    private val toolsById: Map<ToolId, Tool> = this.modules.flatMap { it.tools }.associateBy { it.toolId }

    init {
        // A method name is what credentials, amr entries and tool ids are keyed by.
        val duplicateMethods = modules.groupBy { it.method }.filterValues { it.size > 1 }.keys
        check(duplicateMethods.isEmpty()) { "More than one tool module for method $duplicateMethods" }
    }

    override fun modules(): List<ToolModule> = modules

    override fun toolOf(toolId: ToolId): Tool =
        toolsById[toolId] ?: throw OrchestratorException.notFound(Text("Unknown tool"), "toolId=${toolId}")

    companion object {
        /** A catalog of exactly these modules - for tests and for the domain's own fixtures. */
        fun of(vararg modules: ToolModule): ToolHandlerRegistry = ToolHandlerRegistry(modules.toList())
    }
}
