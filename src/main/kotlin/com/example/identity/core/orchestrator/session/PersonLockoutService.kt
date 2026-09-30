package com.example.identity.core.orchestrator.session

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration

/**
 * Person-level lockout after failed IDENT attempts. IDENT failures are a brute-force target:
 * `ident-fsc` checks one secret against a KVNR, and a hit creates or takes over that person's
 * account. Keyed by personId, since no account is known yet. A lock is not its own error but the
 * tool's ordinary failure, so the response does not reveal which KVNRs exist.
 */
@Service
@Transactional
class PersonLockoutService(private val counter: RateLimitCounter) {

    fun isLocked(personId: String): Boolean = counter.isLocked(RateLimitScope.PERSON, key(personId))

    fun recordFailure(personId: String) =
        counter.recordFailure(RateLimitScope.PERSON, key(personId), MAX_FAILURES, LOCKOUT_DURATION)

    fun recordSuccess(personId: String) = counter.reset(RateLimitScope.PERSON, key(personId))

    private fun key(personId: String) = personId

    companion object {
        private const val MAX_FAILURES = 5
        private val LOCKOUT_DURATION: Duration = Duration.ofMinutes(15)
    }
}
