package com.example.identity.core.orchestrator.admin

import com.example.identity.contract.tool_api.ToolVersion
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.tool.ToolAvailabilityService
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.tool_api.ToolRole
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** One version of a tool and whether it is switched on for the channel (ADR-51). */
data class ToolVersionAvailability(
    /** The version's wire form, as clients declare it and as the switch is addressed. */
    @field:Schema(example = "auth-sms@1") val tool: String,
    @field:Schema(example = "1") val version: Int,
    @field:Schema(example = "true") val enabled: Boolean,
    @field:Schema(example = "Wartungsfenster bis 18 Uhr") val reason: String?
)

/** One tool in a channel's order, with the switch of each of its versions. */
data class ToolAvailabilityEntry(
    @field:Schema(example = "auth-sms") val toolId: String,
    @field:Schema(example = "sms") val method: String,
    /** Which kind of selection list the tool appears in - the order only matters among tools of one role. */
    @field:Schema(example = "KNOWN_ACCOUNT_AUTH") val role: ToolRole,
    val versions: List<ToolVersionAvailability>
)

/** One channel type's tools, in the order that channel offers them. */
data class ChannelToolAvailability(
    @field:Schema(example = "APP") val channel: ChannelType,
    val tools: List<ToolAvailabilityEntry>
)

data class ToolAvailabilityPutRequest(
    @field:Schema(example = "false") val enabled: Boolean,
    @field:Schema(example = "Wartungsfenster bis 18 Uhr") val reason: String? = null
)

data class ToolOrderPutRequest(
    @field:Schema(example = """["auth-password", "auth-sms"]""") val toolIds: List<String>
)

/**
 * The operator's say over tools, per channel type (docs/03-tool-architektur.md, availability):
 * switch one version of a tool off for the App or the Web channel alone (ADR-51), and set the order
 * each channel offers its tools in. Both take effect on the next step of any journey, no redeploy needed. Behind the
 * admin login like everything under [ADMIN_API] (AdminSecurityConfig).
 */
@RestController
@RequestMapping("$ADMIN_API/tools")
@Tag(name = "Admin: tool availability", description = "Operator kill-switch and order for tools, per channel type")
class ToolAvailabilityController(
    private val toolAvailabilityService: ToolAvailabilityService,
    private val toolRegistry: ToolHandlerRegistry
) {

    @GetMapping("/availability")
    @Operation(summary = "Every catalog tool per channel type, in that channel's order, with the switch of each version")
    fun list(): List<ChannelToolAvailability> {
        val catalog = toolRegistry.tools()
        val descriptorOf = catalog.associateBy { it.toolId }
        val disabled = toolAvailabilityService.disabledEntries()
        return ChannelType.entries.map { channel ->
            val off = disabled.filter { it.channel == channel }.associate { it.tool to it.reason }
            ChannelToolAvailability(
                channel,
                toolAvailabilityService.ordered(channel, catalog.map { it.toolId }).map { toolId ->
                    val descriptor = descriptorOf.getValue(toolId)
                    val versions = descriptor.versions.map { version ->
                        val tool = ToolVersion(toolId, version)
                        ToolVersionAvailability(tool.toString(), version, tool !in off, off[tool])
                    }
                    ToolAvailabilityEntry(toolId.value, descriptor.method, descriptor.role, versions)
                }
            )
        }
    }

    @PutMapping("/{tool}/availability/{channel}")
    @Operation(
        summary = "Enable or disable one tool version for one channel type",
        description = "{tool} is the version's wire form, e.g. enroll-sms@2. Takes effect on the next step computed for " +
            "any channel of that type - no restart needed. A version the server does not serve is a 400."
    )
    fun put(@PathVariable tool: String, @PathVariable channel: ChannelType, @RequestBody request: ToolAvailabilityPutRequest) {
        val version = ToolVersion.parse(tool)
        if (request.enabled) toolAvailabilityService.enable(version, channel)
        else toolAvailabilityService.disable(version, channel, request.reason)
    }

    @PutMapping("/order/{channel}")
    @Operation(
        summary = "Set the order a channel type offers its tools in",
        description = "First entry is offered first. Tools left out move behind the listed ones. Applies to every selection " +
            "screen of that channel type (login, identification, enrollment) from its next step on."
    )
    fun putOrder(@PathVariable channel: ChannelType, @RequestBody request: ToolOrderPutRequest) {
        toolAvailabilityService.setOrder(channel, request.toolIds)
    }
}
