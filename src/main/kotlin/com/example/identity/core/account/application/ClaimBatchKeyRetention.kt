package com.example.identity.core.account.application

import com.example.identity.core.account.infrastructure.ClaimBatchKeyRepository
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Instant
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * Erases claim batches whose retention ended (`account.claims.retention`, docs/07-betrieb.md
 * Abschnitt 3): each batch in its own transaction, retraction rows and key deletion together.
 */
@Component
class ClaimBatchKeyRetention(
    private val batchKeys: ClaimBatchKeyRepository,
    private val claimLedger: ClaimLedger,
    private val transactions: TransactionTemplate,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 300_000)
    fun sweep() {
        val expired = purge(clock.instant())
        meterRegistry.counter("identity.retention.deleted", "table", "claim_batch_key").increment(expired.toDouble())
    }

    /** @return how many batches were erased. */
    fun purge(now: Instant): Int {
        var total = 0
        while (true) {
            val batch = batchKeys.findExpiredBefore(now, Pageable.ofSize(BATCH))
            if (batch.isEmpty()) return total
            batch.forEach { key -> transactions.executeWithoutResult { claimLedger.expire(key, now) } }
            total += batch.size
        }
    }

    private companion object {
        const val BATCH = 500
    }
}
