package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.AuthIntent
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

@Entity
@Table(schema = "orchestrator", name = "channel_session")
class ChannelSession(
    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 32)
    var channel: ChannelType? = null,

    /** APP only (docs/02-domaenenmodell.md Abschnitt 1). WEB channels binding via [channelBinding]. */
    @Column(name = "binding_key_ref", length = 64)
    var bindingKeyRef: String? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null,
    now: Instant
) {
    /**
     * WEB-only channel binding (docs/02-domaenenmodell.md Abschnitt 1): this flow run's own
     * `channelSessionId`, carried in the peer-auth assertion. [ChannelAccessGuard] checks it, so a
     * leaked `channelSessionId` plus any valid Keycloak signature cannot hijack the channel. Not the
     * durable `UserSessionModel` id, which concurrent flow runs of one SSO session would share.
     */
    @Column(name = "channel_binding", length = 64)
    var channelBinding: String? = null

    /**
     * WEB only: Keycloak's durable `UserSessionModel` id, known once a flow has completed
     * (set by `KcChannelService.restoreData`). Only [com.example.identity.core.orchestrator.retention.RetentionJob]
     * reads it, to ask whether the session is still alive. Never used for authorization.
     */
    @Column(name = "kc_durable_session_id", length = 64)
    var durableKcSessionId: String? = null

    /**
     * Self-assigned, so the kc facade can set its client-chosen id before the first save for
     * idempotent upserts (docs/05-api.md Abschnitt 3). APP callers get a random id.
     */
    @Id
    @Column(name = "id", nullable = false)
    var channelSessionId: UUID? = null

    /** The account of [subject], if the subject is one. Written only through [subject]. */
    @Column(name = "account_id")
    var accountId: Long? = null
        protected set

    /** The invitation of [subject], if the subject is one. Written only through [subject]. */
    @Column(name = "invitation", length = 64)
    var invitation: String? = null
        protected set

    /**
     * Whom this channel works for: an account, or the invitation a one-time password opened
     * (ADR-48 (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md)); `null` while nobody is known.
     * Stored as two columns, of which the database allows at most one (`ck_channel_session_one_subject`).
     */
    var subject: Subject?
        get() = accountId?.let(Subject::Account) ?: invitation?.let(Subject::Invitation)
        set(value) {
            accountId = (value as? Subject.Account)?.id
            invitation = (value as? Subject.Invitation)?.id
        }

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    var state: ChannelState? = null

    /** APP only (docs/05-api.md Abschnitt 3): the WEB channel has no App-style tokens to bind. */
    @Column(name = "app_token_session_id")
    var appTokenSessionId: UUID? = null

    /** Both channel types (docs/05-api.md Abschnitt 3): the evidence itself. */
    @Column(name = "session_evidence_id")
    var sessionEvidenceId: UUID? = null

    /**
     * Whether at least one factor was proven on this channel. Weaker than `state == AUTHENTICATED`,
     * which needs the full required ACR. [accountId] alone is not this: a recognized device carries
     * an accountId before any proof. Account details may be revealed only after a proof.
     */
    val hasProvenFactor: Boolean
        get() = sessionEvidenceId != null

    /**
     * The channel's durable lower bound; survives individual journeys. A step-up run's target lives
     * in that run's state (docs/04-orchestrierung.md #8).
     */
    @Column(name = "acr_floor", length = 16)
    var acrFloor: String? = null

    /**
     * The intent this channel was entered with. Resume and cancel must restart the same intent;
     * otherwise an abandoned lookup login would fall back to whatever the device is linked to.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "entry_intent", nullable = false, length = 32)
    var entryIntent: AuthIntent = AuthIntent.FAST_ACCESS

    /**
     * The toolIds this client declared at channel creation, fixed for the channel's lifetime
     * (docs/03-tool-architektur.md). The other axis of availability is ToolAvailabilityService.
     * A JSON column, not an element-collection table, to avoid a join on the hottest path.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "available_tools")
    var availableClientTools: MutableSet<String> = mutableSetOf()

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = now

    @Column(name = "last_accessed_at", nullable = false)
    var lastAccessedAt: Instant? = now

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    init {
        channelSessionId = UUID.randomUUID()
        state = ChannelState.ANONYMOUS
    }

    fun touch(now: Instant) {
        lastAccessedAt = now
    }

    fun isExpiredAt(now: Instant): Boolean = expiresAt?.let { now.isAfter(it) } ?: false
}

/* Set on the first save, never null afterwards. The assumption stands once, next to the column. */
val ChannelSession.id: UUID get() = checkNotNull(channelSessionId) { "ChannelSession not saved yet" }
val ChannelSession.channelType: ChannelType get() = checkNotNull(channel) { "ChannelSession $channelSessionId without a channel type" }
