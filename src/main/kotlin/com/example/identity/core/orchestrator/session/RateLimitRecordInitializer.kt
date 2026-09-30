package com.example.identity.core.orchestrator.session

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Creates a missing counter row for [RateLimitCounter] in its own transaction. A separate bean,
 * because a `REQUIRES_NEW` call inside the same bean would bypass the proxy, and a duplicate-key
 * violation must not poison the caller's transaction (PostgreSQL aborts it). No vendor-specific
 * upsert: H2 rejects `ON CONFLICT`. A row at 0 whose caller rolls back is harmless.
 */
@Component
class RateLimitRecordInitializer(private val repository: RateLimitRecordRepository, private val clock: Clock) {

    /**
     * Idempotent. A concurrent creation surfaces as a unique violation, which [RateLimitCounter]
     * treats as success: the row exists before the retry.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun createIfAbsent(scope: String, subject: String) {
        if (repository.existsById(RateLimitRecordId(scope, subject))) return
        repository.insertAtZero(scope, subject, clock.instant())
    }
}
