package com.example.identity.core.orchestrator.domain

import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId

/**
 * What the domain may ask about the tools: their self-descriptions. The strategies and the policy
 * read the catalog through this, never through the Spring component that assembles it
 * (`ToolHandlerRegistry`) - so the domain stays free of the framework
 * (docs/adr/ADR-040-fachkern-im-paket-domain.md).
 */
interface ToolCatalog {
    fun descriptors(): List<ToolDescriptor>

    /** The descriptor of [toolId]; unknown ids are a broken assumption, not a case to branch on. */
    fun descriptorOf(toolId: ToolId): ToolDescriptor

    /**
     * The [role] procedure of [method], or `null`. A method alone names several procedures with
     * different factor types (`enroll-qr`, `auth-qr`); `(method, role)` names one.
     */
    fun descriptorOf(method: String, role: ToolRole): ToolDescriptor? =
        descriptors().firstOrNull { it.method == method && it.role == role }
}
