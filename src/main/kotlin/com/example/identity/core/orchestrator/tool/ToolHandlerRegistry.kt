package com.example.identity.core.orchestrator.tool

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.ToolCatalog
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolId
import org.springframework.stereotype.Component

/**
 * Aggregates every handler's self-description (docs/03-tool-architektur.md #1) into the tool
 * catalog. Purely a descriptor catalog: each tool's controller calls its handler directly (ADR-1),
 * so there is no toolId-to-handler dispatch.
 */
@Component
class ToolHandlerRegistry(descriptors: List<ToolDescriptor>) : ToolCatalog {
    private val descriptorsByToolId: Map<ToolId, ToolDescriptor> = descriptors.associateBy { it.toolId }

    init {
        // (method, role) identifies one procedure; callers resolve a single descriptor by it. A
        // duplicate would silently pick whichever comes first, so fail at startup.
        val duplicates = descriptorsByToolId.values
            .groupBy { it.method to it.role }
            .filterValues { it.size > 1 }
        check(duplicates.isEmpty()) {
            val details = duplicates.entries.joinToString("; ") { (key, group) ->
                "${key.first}/${key.second}: ${group.map { it.toolId }}"
            }
            "Duplicate (method, role) in tool catalog: $details"
        }
        // Only the Personenverzeichnis vouches for a KVNR. Identity matching has no path for a
        // KVNR a tool merely read.
        val unvouchedKvnr = descriptorsByToolId.values.filter { descriptor ->
            descriptor.claims.any { it.attributeType == AttributeType.KVNR && it.source != ClaimSource.PERSON_DIRECTORY }
        }
        check(unvouchedKvnr.isEmpty()) {
            "Only the Personenverzeichnis may vouch for a KVNR, but ${unvouchedKvnr.map { it.toolId }} declare one from elsewhere"
        }
        // Every identification must be findable again by name, first name and date of birth, even
        // after the account is deleted (the change log's search key, ADR-39).
        val unfindable = descriptorsByToolId.values.filter { descriptor ->
            descriptor.role == ToolRole.IDENTIFICATION &&
                !descriptor.claims.map { it.attributeType }.containsAll(FINDABLE_BY)
        }
        check(unfindable.isEmpty()) {
            "Every identification procedure must declare $FINDABLE_BY, but ${unfindable.map { it.toolId }} do not"
        }
    }

    override fun descriptorOf(toolId: ToolId): ToolDescriptor =
        descriptorsByToolId[toolId] ?: throw OrchestratorException.notFound(Text("Unknown tool"), "toolId=${toolId}")

    override fun descriptors(): List<ToolDescriptor> = descriptorsByToolId.values.toList()

    private companion object {
        val FINDABLE_BY = setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)
    }
}
