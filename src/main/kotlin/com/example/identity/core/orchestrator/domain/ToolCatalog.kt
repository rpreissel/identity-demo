package com.example.identity.core.orchestrator.domain

import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolModule

/**
 * What the domain may ask about the tools: the procedures and their tools. The strategies and the
 * policy read the catalog through this, never through the Spring component that assembles it
 * (`ToolHandlerRegistry`) - so the domain stays free of the framework
 * (docs/adr/ADR-040-fachkern-im-paket-domain.md).
 */
interface ToolCatalog {
    /** Every procedure, each with its tools. */
    fun modules(): List<ToolModule>

    /** Every tool of every procedure. */
    fun tools(): List<Tool> = modules().flatMap { it.tools }

    /** The tool with [toolId]; unknown ids are a broken assumption, not a case to branch on. */
    fun toolOf(toolId: ToolId): Tool

    /**
     * The [role] tool of [method], or `null`. A method alone names several tools with different
     * factor types (`enroll-qr`, `auth-qr`); `(method, role)` names one.
     */
    fun toolOf(method: String, role: ToolRole): Tool? =
        moduleOf(method)?.tools?.firstOrNull { it.role == role }

    /**
     * The procedure named [method]: what an enrolled credential of it reaches, whichever of its
     * tools proves it. All tools of a method share their module, so the answer does not depend on
     * which tool is asked.
     */
    fun moduleOf(method: String): ToolModule? = modules().firstOrNull { it.method == method }
}
