package com.example.identity.core.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

/**
 * The orchestrator's own counting spaces. Part of the primary key, so the spaces never collide:
 * accountId 42 and personId 42 are different subjects. Budgets of tool modules use their
 * namespace instead (`tool_api.AttemptBudget`, ADR-44); it contains a dot, an enum name never does.
 */
enum class ThrottleScope {
    /** AUTH attempts against one account (docs/04-orchestrierung.md). */
    ACCOUNT,

    /** Wrong passwords for one admin user name. */
    ADMIN,

    /**
     * IDENT attempts against one person. Separate from [ACCOUNT]: an identification runs before
     * any account is known, yet `ident-fsc` guesses one secret whose success adopts an account.
     */
    PERSON,

    /**
     * Channel creations per DPoP binding key. The others bound attempts within a journey chain;
     * this one bounds how cheaply an attacker can mint fresh chains.
     */
    BINDING_KEY
}

@Embeddable
data class AttemptThrottleId(
    /** A [ThrottleScope] name or a module budget's namespace. */
    @Column(name = "scope", nullable = false, length = 64)
    var scope: String? = null,

    @Column(name = "subject", nullable = false, length = 128)
    var subject: String? = null
) : Serializable

/**
 * Brute-force counter for one (scope, subject) pair. Wider than per tool session or journey,
 * because a client can restart those, and lookup login knows no accountId before success.
 * Pure data; the rules live in the named services ([AccountLockoutService], [PersonLockoutService],
 * [ChannelCreationThrottleService]) and in the tool modules' budgets (ADR-44).
 */
@Entity
@Table(schema = "orchestrator", name = "attempt_throttle")
class AttemptThrottle(
    @EmbeddedId
    var id: AttemptThrottleId? = null
) {
    @Column(name = "failed_count", nullable = false)
    var failedCount: Int = 0

    @Column(name = "locked_until")
    var lockedUntil: Instant? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null

    init {
        updatedAt = Instant.now()
    }
}
