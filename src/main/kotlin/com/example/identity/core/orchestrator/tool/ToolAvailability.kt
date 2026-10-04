package com.example.identity.core.orchestrator.tool

import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.contract.tool_api.ToolVersion
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/**
 * Composite key of [ToolAvailability]: one row per tool version and channel type. The version is
 * kept in its wire form, since JPA allows no converter on an id; build it with [of].
 */
data class ToolAvailabilityKey(
    var wireTool: String? = null,
    var channel: ChannelType? = null
) : Serializable {
    companion object {
        fun of(tool: ToolVersion, channel: ChannelType) = ToolAvailabilityKey(tool.toString(), channel)
    }
}

/**
 * The operator's switch for one tool version in one channel type (docs/03-tool-architektur.md,
 * availability; ADR-51), stored as its wire form `enroll-sms@2`. Absence of a row means
 * enabled - only versions that were ever switched live here, so the catalog never needs pre-seeding.
 */
@Entity
@Table(schema = "orchestrator", name = "tool_availability")
@IdClass(ToolAvailabilityKey::class)
class ToolAvailability(
    /** The version's wire form; read it as [tool]. */
    @Id
    @Column(name = "tool", nullable = false, length = 60)
    var wireTool: String? = null,

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    var channel: ChannelType? = null
) {
    constructor(tool: ToolVersion, channel: ChannelType) : this(tool.toString(), channel)

    val tool: ToolVersion get() = ToolVersion.parse(checkNotNull(wireTool))

    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = true

    @Column(name = "reason", length = 255)
    var reason: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}

/** Composite key of [ToolOrder]: one row per tool and channel type. */
data class ToolOrderKey(
    var toolId: String? = null,
    var channel: ChannelType? = null
) : Serializable

/**
 * A tool's rank in one channel type's selection lists (ADR-32). Per tool, not per version: a
 * channel offers each tool in the one version its client declared.
 */
@Entity
@Table(schema = "orchestrator", name = "tool_order")
@IdClass(ToolOrderKey::class)
class ToolOrder(
    @Id
    @Column(name = "tool_id", nullable = false, length = 50)
    var toolId: String? = null,

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    var channel: ChannelType? = null,

    /** 0-based rank in this channel's selection lists. */
    @Column(name = "position", nullable = false)
    var position: Int = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
)
