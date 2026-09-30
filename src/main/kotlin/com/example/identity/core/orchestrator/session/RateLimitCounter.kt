package com.example.identity.core.orchestrator.session

import org.springframework.dao.DataIntegrityViolationException
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * The counting and locking mechanics under the named rate limit services and the tool modules'
 * budgets ([ModuleRateLimits], ADR-44). Limits and keys belong to those callers. Every
 * mutation is one atomic statement on the counter row, never load-modify-save: otherwise the
 * budget becomes "limit x parallelism" (docs/07-betrieb.md #4). A missing row is created by
 * [RateLimitRecordInitializer], so the steady state stays at one statement.
 */
@Component
@Transactional
class RateLimitCounter(
    private val repository: RateLimitRecordRepository,
    private val rowInitializer: RateLimitRecordInitializer,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {

    /** Until when [subject] is locked: `null` if never, possibly in the past. */
    fun lockedUntil(scope: RateLimitScope, subject: String): Instant? = repository.findLockedUntil(scope.name, subject)

    fun isLocked(scope: RateLimitScope, subject: String): Boolean {
        val lockedUntil = repository.findLockedUntil(scope.name, subject) ?: return false
        return clock.instant().isBefore(lockedUntil).also { if (it) countBlocked(scope.name) }
    }

    fun recordFailure(scope: RateLimitScope, subject: String, maxFailures: Int, lockout: Duration) {
        val now = clock.instant()
        val lockUntil = now.plus(lockout)
        if (repository.incrementFailure(scope.name, subject, maxFailures, lockUntil, now) == 0) {
            ensureRow(scope.name, subject)
            repository.incrementFailure(scope.name, subject, maxFailures, lockUntil, now)
        }
    }

    fun reset(scope: RateLimitScope, subject: String) = reset(scope.name, subject)

    /** [scope] is a [RateLimitScope] name or a module budget's namespace. */
    fun reset(scope: String, subject: String) {
        repository.resetCounter(scope, subject, clock.instant())
    }

    fun recordWindowedAttempt(scope: RateLimitScope, subject: String, maxPerWindow: Int, window: Duration): Boolean =
        recordWindowedAttempt(scope.name, subject, maxPerWindow, window)

    /**
     * Rolling window for scopes where every attempt counts: the counter restarts once [window] has
     * passed since the last one. The read-back is safe: the `update` holds the row lock until
     * commit, so each caller reads its own increment. [scope] is a [RateLimitScope] name or a
     * module budget's namespace.
     *
     * @return true while this attempt is within budget, false once it exceeds [maxPerWindow].
     */
    fun recordWindowedAttempt(scope: String, subject: String, maxPerWindow: Int, window: Duration): Boolean {
        val now = clock.instant()
        val windowStart = now.minus(window)
        if (repository.incrementWithinWindow(scope, subject, windowStart, now) == 0) {
            ensureRow(scope, subject)
            repository.incrementWithinWindow(scope, subject, windowStart, now)
        }
        // Fails closed: a counter that cannot be read cannot be shown to be within budget.
        val count = repository.findFailedCount(scope, subject) ?: return false
        return (count <= maxPerWindow).also { if (!it) countBlocked(scope) }
    }

    /** A concurrent request that created the row first is the wanted outcome. */
    private fun ensureRow(scope: String, subject: String) {
        try {
            rowInitializer.createIfAbsent(scope, subject)
        } catch (_: DataIntegrityViolationException) {
            // The row exists now, created by the rival.
        }
    }

    /** `identity.ratelimit.blocked` by scope (docs/07-betrieb.md Abschnitt 7). A rise is an attack or a bug. */
    private fun countBlocked(scope: String) {
        meterRegistry.counter(BLOCKED_METRIC, "scope", scope).increment()
    }

    private companion object {
        const val BLOCKED_METRIC = "identity.ratelimit.blocked"
    }
}
