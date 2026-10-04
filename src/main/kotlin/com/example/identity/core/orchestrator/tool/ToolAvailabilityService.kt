package com.example.identity.core.orchestrator.tool

import com.example.identity.demo.demo_mode.DemoMode
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolVersion
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * The backend half of tool availability per channel type (the other half is
 * [ChannelSession.availableClientTools]). The operator switches tool versions off and sets the
 * order of tools per channel (ADR-32, ADR-51). Read live, so a change applies to the next step of
 * a running journey.
 */
@Service
@Transactional
class ToolAvailabilityService(
    private val repository: ToolAvailabilityRepository,
    private val orderRepository: ToolOrderRepository,
    private val toolRegistry: ToolHandlerRegistry,
    private val defaults: ToolDefaults,
    private val demoMode: DemoMode,
    private val clock: Clock
) {
    /** Every version the server serves. */
    private val servedVersions: Set<ToolVersion> =
        toolRegistry.tools().flatMapTo(mutableSetOf()) { tool -> tool.versions.map { ToolVersion(tool.toolId, it) } }

    /**
     * Demo-only tools are off outside `demo.mode`, in every version. Kept apart from the operator's
     * switches, so no setting can turn one back on.
     */
    private val demoOnlyVersions: Set<ToolVersion> =
        if (demoMode.on) emptySet()
        else toolRegistry.tools().filter { it.demoOnly != null }
            .flatMapTo(mutableSetOf()) { tool -> tool.versions.map { ToolVersion(tool.toolId, it) } }

    fun isEnabled(tool: ToolVersion, channel: ChannelType): Boolean =
        tool !in demoOnlyVersions && (repository.findByIdOrNull(ToolAvailabilityKey.of(tool, channel))?.enabled ?: true)

    /** The versions switched off for [channel]. */
    fun disabledTools(channel: ChannelType): Set<ToolVersion> =
        repository.findByChannel(channel).filterNot { it.enabled }.mapTo(mutableSetOf()) { it.tool } + demoOnlyVersions

    /** Every version that is switched off in any channel - channel, tool, reason. */
    fun disabledEntries(): List<ToolAvailability> = repository.findAll().filterNot { it.enabled }

    /** Switches [tool] off for [channel]. Only a version the server serves. */
    fun disable(tool: ToolVersion, channel: ChannelType, reason: String?) =
        update(served(tool), channel) { it.enabled = false; it.reason = reason }

    fun enable(tool: ToolVersion, channel: ChannelType) =
        update(served(tool), channel) { it.enabled = true; it.reason = null }

    fun hasAnySetting(): Boolean = repository.count() > 0 || orderRepository.count() > 0

    /**
     * Replaces every setting with the demo's preset ([ToolDefaults]): order and locks per channel.
     * A preset lock without a version (`auth-qr`) locks every version of the tool.
     */
    fun applyDefaults() {
        repository.deleteAll()
        orderRepository.deleteAll()
        repository.flush()
        orderRepository.flush()
        defaults.channels.forEach { (channel, preset) ->
            setOrder(channel, preset.order)
            preset.disabled.flatMap(::versionsOf).forEach { disable(it, channel, PRESET_REASON) }
        }
    }

    /**
     * Sets [channel]'s ranking to exactly [toolIds] (first = offered first); every tool not in the
     * list loses its rank and moves behind the ranked ones.
     */
    fun setOrder(channel: ChannelType, toolIds: List<String>) {
        orderRepository.deleteAll(orderRepository.findByChannel(channel).filter { it.toolId !in toolIds })
        toolIds.forEachIndexed { index, toolId ->
            val entry = orderRepository.findByIdOrNull(ToolOrderKey(toolId, channel)) ?: ToolOrder(toolId = toolId, channel = channel)
            entry.position = index
            entry.updatedAt = clock.instant()
            orderRepository.save(entry)
        }
    }

    /** toolId -> rank for [channel]; unranked tools are absent. */
    fun rankOf(channel: ChannelType): Map<String, Int> =
        orderRepository.findByChannel(channel).associate { it.toolId!! to it.position }

    /**
     * [tools] in [channel]'s order: ranked ones first, the rest by role, then method. The admin
     * page shows the same default, so it lists what a user is offered.
     */
    fun ordered(channel: ChannelType, tools: Collection<ToolId>): List<ToolId> {
        val rank = rankOf(channel)
        return tools.sortedWith(compareBy<ToolId> { rank[it.value] ?: Int.MAX_VALUE }.then(defaultOrder))
    }

    private val defaultOrder: Comparator<ToolId> = compareBy(
        { ROLE_ORDER.indexOf(toolRegistry.toolOf(it).role) },
        { toolRegistry.toolOf(it).method },
        { it.value }
    )

    /** A preset entry as versions: `auth-qr@1` itself, a plain `auth-qr` every version the server serves. */
    private fun versionsOf(entry: String): List<ToolVersion> =
        if ('@' in entry) listOf(ToolVersion.parse(entry))
        else toolRegistry.toolOf(ToolId(entry)).versions.map { ToolVersion(ToolId(entry), it) }

    private fun served(tool: ToolVersion): ToolVersion {
        require(tool in servedVersions) { "Not a tool version the server serves" }
        return tool
    }

    companion object {
        const val PRESET_REASON = "Voreinstellung der Demo"

        /**
         * Each role is one kind of selection list, so ranking matters only within a role. Listed in
         * the order a user meets them.
         */
        val ROLE_ORDER = listOf(
            ToolRole.IDENTIFICATION, ToolRole.CORRELATION, ToolRole.ENROLLMENT,
            ToolRole.KNOWN_ACCOUNT_AUTH, ToolRole.ACCOUNT_LOOKUP_AUTH, ToolRole.ATTESTATION, ToolRole.PEER_APPROVAL
        )
    }

    private fun update(tool: ToolVersion, channel: ChannelType, change: (ToolAvailability) -> Unit) {
        val entry = repository.findByIdOrNull(ToolAvailabilityKey.of(tool, channel))
            ?: ToolAvailability(tool, channel)
        change(entry)
        entry.updatedAt = clock.instant()
        repository.save(entry)
    }
}
