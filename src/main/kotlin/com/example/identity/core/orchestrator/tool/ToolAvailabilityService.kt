package com.example.identity.core.orchestrator.tool

import com.example.identity.demo.demo_mode.DemoMode
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.contract.tool_api.MethodRole
import com.example.identity.contract.tool_api.ToolId
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * The backend half of tool availability per channel type (the other half is
 * [com.example.identity.core.orchestrator.session.ChannelSession.availableClientTools]). The operator
 * switches tools off and sets their order per channel (ADR-32). Read live, so a change applies to
 * the next step of a running journey.
 */
@Service
@Transactional
class ToolAvailabilityService(
    private val repository: ToolAvailabilityRepository,
    private val toolRegistry: ToolHandlerRegistry,
    private val defaults: ToolDefaults,
    private val demoMode: DemoMode,
    private val clock: Clock
) {
    /**
     * Demo-only tools are off outside `demo.mode`. Kept apart from the operator's switches, so no
     * setting can turn one back on.
     */
    private val demoOnlyToolIds: Set<String> =
        if (demoMode.on) emptySet() else toolRegistry.descriptors().filter { it.demoOnly != null }.mapTo(mutableSetOf()) { it.toolId.value }

    fun isEnabled(toolId: String, channel: ChannelType): Boolean =
        toolId !in demoOnlyToolIds && (repository.findByIdOrNull(ToolAvailabilityKey(toolId, channel))?.enabled ?: true)

    fun disabledToolIds(channel: ChannelType): Set<String> =
        repository.findByChannel(channel).filterNot { it.enabled }.mapTo(mutableSetOf()) { it.toolId!! } + demoOnlyToolIds

    /** Every tool that is switched off in any channel - channel, toolId, reason. */
    fun disabledEntries(): List<ToolAvailability> = repository.findAll().filterNot { it.enabled }

    fun disable(toolId: String, channel: ChannelType, reason: String?) =
        update(toolId, channel) { it.enabled = false; it.reason = reason }

    fun enable(toolId: String, channel: ChannelType) =
        update(toolId, channel) { it.enabled = true; it.reason = null }

    fun hasAnySetting(): Boolean = repository.count() > 0

    /** Replaces every setting with the demo's preset ([ToolDefaults]): order and locks per channel. */
    fun applyDefaults() {
        repository.deleteAll()
        repository.flush()
        defaults.channels.forEach { (channel, preset) ->
            setOrder(channel, preset.order)
            preset.disabled.forEach { disable(it, channel, PRESET_REASON) }
        }
    }

    /**
     * Sets [channel]'s ranking to exactly [toolIds] (first = offered first); every tool not in the
     * list loses its rank and moves behind the ranked ones.
     */
    fun setOrder(channel: ChannelType, toolIds: List<String>) {
        repository.findByChannel(channel).filter { it.position != null && it.toolId !in toolIds }
            .forEach { entry -> update(entry.toolId!!, channel) { it.position = null } }
        toolIds.forEachIndexed { index, toolId -> update(toolId, channel) { it.position = index } }
    }

    /** toolId -> rank for [channel]; unranked tools are absent. */
    fun rankOf(channel: ChannelType): Map<String, Int> =
        repository.findByChannel(channel).mapNotNull { e -> e.position?.let { e.toolId!! to it } }.toMap()

    /**
     * [tools] in [channel]'s order: ranked ones first, the rest by role, then method. The admin
     * page shows the same default, so it lists what a user is offered.
     */
    fun ordered(channel: ChannelType, tools: Collection<ToolId>): List<ToolId> {
        val rank = rankOf(channel)
        return tools.sortedWith(compareBy<ToolId> { rank[it.value] ?: Int.MAX_VALUE }.then(defaultOrder))
    }

    private val defaultOrder: Comparator<ToolId> = compareBy(
        { ROLE_ORDER.indexOf(toolRegistry.descriptorOf(it).role) },
        { toolRegistry.descriptorOf(it).method },
        { it.value }
    )

    companion object {
        const val PRESET_REASON = "Voreinstellung der Demo"

        /**
         * Each role is one kind of selection list, so ranking matters only within a role. Listed in
         * the order a user meets them.
         */
        val ROLE_ORDER = listOf(
            MethodRole.IDENTIFICATION, MethodRole.CORRELATION, MethodRole.ENROLLMENT,
            MethodRole.IDENTIFIED_AUTH, MethodRole.LOOKUP_AUTH, MethodRole.ATTESTATION, MethodRole.PEER_APPROVAL
        )
    }

    private fun update(toolId: String, channel: ChannelType, change: (ToolAvailability) -> Unit) {
        val entry = repository.findByIdOrNull(ToolAvailabilityKey(toolId, channel))
            ?: ToolAvailability(toolId = toolId, channel = channel)
        change(entry)
        entry.updatedAt = clock.instant()
        repository.save(entry)
    }
}
