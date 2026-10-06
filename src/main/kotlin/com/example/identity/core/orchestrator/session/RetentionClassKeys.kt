package com.example.identity.core.orchestrator.session

import com.example.identity.core.account.DataKeyWrapping
import com.example.identity.core.account.WrappedDataKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** An unwrapped data key with the id a row stores to find it again. */
class OpenDataKey(val keyId: String, val key: ByteArray)

/**
 * The data keys of the orchestrator's short-lived data (ADR-53): one per retention class and UTC
 * day, created on first use, wrapped with the KEK through the account module. Rows are never
 * re-keyed; a row simply names the key of its day. Unwrapped keys are cached per instance, so a
 * read costs no unwrapping, and with a KMS behind the KEK one call per key and day.
 */
@Component
class RetentionClassKeys(
    private val repository: DataKeyRepository,
    private val wrapping: DataKeyWrapping,
    /** The same value `RetentionJob` sweeps tool sessions with (docs/07-betrieb.md Abschnitt 3). */
    @Value("\${tool-session.retention:PT24H}") private val toolSessionRetention: Duration,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private val cache = ConcurrentHashMap<String, ByteArray>()
    // Creation commits on its own: the row must exist for other transactions and instances at once.
    private val newTransaction = TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /** Today's key for tool working data; the first call of a day creates it. */
    fun currentToolSessionKey(): OpenDataKey {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val keyId = "$TOOL_SESSION:$today"
        return OpenDataKey(keyId, cache[keyId] ?: (keyOf(keyId) ?: create(keyId, today)))
    }

    /** The key a row names, or `null` once it was retired - that row's data is gone for good. */
    fun keyOf(keyId: String): ByteArray? =
        cache[keyId] ?: repository.findById(keyId).orElse(null)?.let { stored ->
            wrapping.unwrap(WrappedDataKey(checkNotNull(stored.wrappedKey), checkNotNull(stored.kekVersion))).also { cache[keyId] = it }
        }

    private fun create(keyId: String, day: LocalDate): ByteArray {
        val key = wrapping.newKey()
        val wrapped = wrapping.wrap(key)
        val dayEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()
        val created = runCatching {
            newTransaction.execute {
                repository.saveAndFlush(
                    DataKey(
                        keyId = keyId, retentionClass = TOOL_SESSION, wrappedKey = wrapped.bytes, kekVersion = wrapped.kekVersion,
                        createdAt = clock.instant(), retireAfter = dayEnd + RETIRE_GRACE + toolSessionRetention
                    )
                )
            }
            true
        }.recover { e ->
            // Another instance (or thread) created today's key first: theirs is the one to use.
            if (e is DataIntegrityViolationException) false else throw e
        }.getOrThrow()
        return if (created) key.also { cache[keyId] = it } else checkNotNull(keyOf(keyId)) { "data key $keyId vanished while being created" }
    }

    companion object {
        const val TOOL_SESSION = "TOOL_SESSION"

        /**
         * How long after its day a key is kept beyond the retention: as long as a journey lives
         * (`RetentionJob`), since tool sessions go with their journey at the latest.
         */
        val RETIRE_GRACE: Duration = Duration.ofDays(7)
    }
}
