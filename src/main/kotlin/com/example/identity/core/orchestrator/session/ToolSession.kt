package com.example.identity.core.orchestrator.session

import org.hibernate.annotations.UuidGenerator
import com.example.identity.core.orchestrator.domain.JourneyId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.persistence.Enumerated
import jakarta.persistence.EnumType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
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
 * Third and shortest-lived session level (docs/03-tool-architektur.md #1). Lifecycle data, plus the
 * tool's own working data as JSON ([data], kept through `ToolSessionData`): toolId comes from the
 * route, stepData from that data. No retry counter: the attempt budget spans the whole journey
 * (docs/04-orchestrierung.md #7).
 */
@Entity
@Table(schema = "orchestrator", name = "tool_session")
class ToolSession(
    @Column(name = "journey_id", nullable = false)
    var journeyId: JourneyId? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null,
    createdAt: Instant
) {
    /** Time-ordered (UUIDv7), so new rows append to the index instead of landing anywhere in it. */
    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false)
    var toolSessionId: ToolSessionId? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: ToolSessionStatus = ToolSessionStatus.RUNNING

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    /** The module and class of [data] (`auth_sms.AuthSmsToolSession`), or `null` before the tool saved any. */
    @Column(name = "data_type", length = 160)
    var dataType: String? = null

    /** The tool's working data as JSON, sealed under the data key [dataKeyId] (ADR-53); `ToolSessionDataService` only. */
    @Column(name = "data")
    var data: ByteArray? = null

    @Column(name = "data_key_id", length = 64)
    var dataKeyId: String? = null

    fun isExpiredAt(now: Instant): Boolean = expiresAt?.let { now.isAfter(it) } ?: false

    /** Still accepts input: running and within its time. */
    fun isUsableAt(now: Instant): Boolean = status == ToolSessionStatus.RUNNING && !isExpiredAt(now)
}

/** Set on the first save, never null afterwards (see `ChannelSession.id`). */
val ToolSession.id: ToolSessionId get() = checkNotNull(toolSessionId) { "ToolSession not saved yet" }
