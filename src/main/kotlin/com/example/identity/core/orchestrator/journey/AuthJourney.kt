package com.example.identity.core.orchestrator.journey

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.JourneyLifecycle
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID
import com.example.identity.core.orchestrator.domain.JourneyFeatureFlag

/**
 * One run of one [Journey]: a guided path with a goal (docs/04-orchestrierung.md #3). Belongs
 * to one ChannelSession and lives shorter than it; at most one journey per channel is
 * [JourneyLifecycle.STARTED]. Pure data: the behaviour lives in the [Journey] object of
 * its [kind] (ADR-2). `next` is derived from [state], never stored.
 */
@Entity
@Table(schema = "orchestrator", name = "auth_journey")
class AuthJourney(
    @Column(name = "channel_session_id", nullable = false)
    var channelSessionId: ChannelSessionId? = null,

    /** Which journey this is a run of. Stored by name (`JourneyConverter`). */
    @Column(name = "intent", nullable = false, length = 32)
    var kind: Journey<*>? = null,

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null,
    createdAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    var journeyId: JourneyId? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle", nullable = false, length = 32)
    var lifecycle: JourneyLifecycle = JourneyLifecycle.STARTED

    /**
     * The account this journey ran for, recorded for audit queries only. Decisions read the
     * channel's `accountId`, the one source. `OrchestratorArchitectureTest` keeps this write-only.
     */
    @Column(name = "account_id")
    var accountId: AccountId? = null

    /** Discriminator of [state], kept as its own column so journeys stay queryable by position. */
    @Column(name = "state_type", nullable = false, length = 100)
    var stateType: String? = null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "state", nullable = false)
    var state: String? = null

    /**
     * Attempts left across the whole journey, not per tool. Otherwise brute force along the chain
     * gets cheaper once an exhausted state moves on (docs/04-orchestrierung.md #7).
     */
    @Column(name = "attempt_budget", nullable = false)
    var attemptBudget: Int = DEFAULT_ATTEMPT_BUDGET

    /** Set on a journey started as another one's precondition; that parent is SUSPENDED meanwhile. */
    @Column(name = "parent_journey_id")
    var parentJourneyId: JourneyId? = null

    /** Whether its parent carries on if this sub-journey is cancelled (`Transition.RequireSubJourney.optional`). */
    @Column(name = "optional_for_parent", nullable = false)
    var optionalForParent: Boolean = false

    /**
     * The [JourneyFeatureFlag]s active when this journey started, as their keys. A running journey
     * keeps them, so flipping a switch only affects journeys started afterwards.
     */
    @Column(name = "feature_flags", nullable = false, length = 500)
    var featureFlagKeys: String = ""

    var featureFlags: Set<JourneyFeatureFlag>
        get() = featureFlagKeys.split(',').filter { it.isNotEmpty() }.mapNotNull(JourneyFeatureFlag::ofKey).toSet()
        set(value) { featureFlagKeys = value.joinToString(",") { it.key } }

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt

    @Column(name = "consumed_at")
    var consumedAt: Instant? = null

    @Version
    @Column(name = "version", nullable = false)
    var version: Long? = null

    fun consume(now: Instant) {
        consumedAt = now
        lifecycle = JourneyLifecycle.CONSUMED
    }

    fun fail() {
        lifecycle = JourneyLifecycle.FAILED
    }

    /** User-initiated abandonment, distinct from [fail] (budget exhausted). */
    fun cancel() {
        lifecycle = JourneyLifecycle.CANCELLED
    }

    fun isExpiredAt(now: Instant): Boolean = expiresAt?.let { now.isAfter(it) } ?: false

    companion object {
        const val DEFAULT_ATTEMPT_BUDGET = 3
    }
}

/* Set on the first save / at creation, never null afterwards - see `ChannelSession.id`. */
val AuthJourney.id: JourneyId get() = checkNotNull(journeyId) { "AuthJourney not saved yet" }
fun AuthJourney.requireKind(): Journey<*> = checkNotNull(kind) { "Journey $journeyId without a kind" }
