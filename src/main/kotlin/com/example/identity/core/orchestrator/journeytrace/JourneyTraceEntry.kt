package com.example.identity.core.orchestrator.journeytrace

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import com.example.identity.core.orchestrator.domain.AuthIntent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/**
 * Rich, per-step trace of a journey's path (docs/04-orchestrierung.md) for debugging, support and
 * the demo - kept 14 days. Not the change log: that is the account's value-free
 * `account.change_log` (ADR-39), which outlives even the account. A different tradeoff, not a copy.
 */
@Entity
@Table(schema = "orchestrator", name = "journey_trace")
class JourneyTraceEntry(
    /** APP-only - null for WEB-channel entries, which have no DPoP binding key (docs/02-domaenenmodell.md Abschnitt 1). */
    @Column(name = "binding_key_ref", length = 64)
    var bindingKeyRef: String? = null,

    /** APP or WEB, kept so the channel stays visible after the session is gone. */
    @Column(name = "channel_type", length = 32)
    var channelType: String? = null,

    /** Null until the channel resolves an account. Unlike [bindingKeyRef], shared by both channels. */
    @Column(name = "account_id")
    var accountId: AccountId? = null,

    @Column(name = "channel_session_id", nullable = false)
    var channelSessionId: ChannelSessionId? = null,

    /** Null for a channel-level event, see [JourneyTraceService.recordForChannel]. */
    @Column(name = "journey_id")
    var journeyId: JourneyId? = null,

    /** Set when this journey ran as another's precondition (docs/04-orchestrierung.md #7); the log nests it. */
    @Column(name = "parent_journey_id")
    var parentJourneyId: JourneyId? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "intent", length = 32)
    var intent: AuthIntent? = null,

    @Column(name = "event_type", nullable = false, length = 100)
    var eventType: String? = null,

    /** The JourneyState subtype at this event (e.g. "AwaitingTan"); null for a channel-level event. */
    @Column(name = "journey_state", length = 100)
    var journeyState: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail")
    var detail: Map<String, Any?>? = null,
    createdAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    var logId: UUID? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
