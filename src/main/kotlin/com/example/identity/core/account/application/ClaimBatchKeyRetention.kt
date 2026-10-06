package com.example.identity.core.account.application

import com.example.identity.core.account.infrastructure.ClaimBatchKeyRepository
import io.micrometer.core.instrument.MeterRegistry
import java.time.Clock
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
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

    /**
     * @return how many batches were erased. A batch that fails is logged and skipped for this run,
     * so one bad row cannot hold up every other account's erasure: the page is ordered by expiry,
     * and the failing key would otherwise come first on every sweep.
     */
    fun purge(now: Instant): Int {
        var total = 0
        val failed = mutableSetOf<UUID>()
        while (true) {
            val page = batchKeys.findExpiredBefore(now, Pageable.ofSize(BATCH)).filter { it.claimBatchId !in failed }
            if (page.isEmpty()) return total
            page.forEach { key ->
                runCatching { transactions.executeWithoutResult { claimLedger.expire(key, now) } }
                    .onSuccess { total++ }
                    .onFailure { e ->
                        failed += checkNotNull(key.claimBatchId)
                        log.error("Claim batch {} of account {} could not be erased, skipped for this run", key.claimBatchId, key.accountId, e)
                    }
            }
        }
    }

    private companion object {
        const val BATCH = 500
        val log = LoggerFactory.getLogger(ClaimBatchKeyRetention::class.java)
    }
}
