package com.example.identity.core.orchestrator.session

import jakarta.persistence.Enumerated
import jakarta.persistence.EnumType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/** How a tool session ends: a state of its own, not an expiry moved to "now". */
enum class ToolSessionStatus {
    RUNNING,
    /** Completed, never completable again. */
    DONE,
    /** Left via Back/Switch. */
    ABANDONED
}

/**
 * Third and shortest-lived session level (docs/03-tool-architektur.md #1). Only technical lifecycle
 * data: toolId comes from the route, stepData from the module's data. No retry counter: the
 * attempt budget spans the whole journey (docs/04-orchestrierung.md #7).
 */
@Entity
@Table(schema = "orchestrator", name = "tool_session")
class ToolSession(
    @Column(name = "journey_id", nullable = false)
    var journeyId: UUID? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    var toolSessionId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: ToolSessionStatus = ToolSessionStatus.RUNNING

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        createdAt = Instant.now()
    }

    val isExpired: Boolean
        get() = expiresAt?.let { Instant.now().isAfter(it) } ?: false

    /** Still accepts input: running and within its time. */
    val isUsable: Boolean
        get() = status == ToolSessionStatus.RUNNING && !isExpired
}

/** Set on the first save, never null afterwards (see `ChannelSession.id`). */
val ToolSession.id: UUID get() = checkNotNull(toolSessionId) { "ToolSession not saved yet" }
