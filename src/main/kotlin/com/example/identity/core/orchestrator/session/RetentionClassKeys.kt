package com.example.identity.core.orchestrator.session

import com.example.identity.core.account.DataKeyWrapping
import com.example.identity.core.account.WrappedDataKey
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

/** An unwrapped data key with the id a row stores to find it again. */
class OpenDataKey(val keyId: String, val key: ByteArray)

/**
 * The data keys of the orchestrator's short-lived data (ADR-53): one per retention class and UTC
 * day, wrapped with the KEK through the account module. Rows are never re-keyed; a row simply names
 * the key of its day. Today's and tomorrow's key are provisioned at start and hourly, so a save
 * never has to create one inside its own transaction; the lazy path exists for a missed run.
 * Unwrapped keys are cached per instance until the key retires, so a read costs no unwrapping and,
 * with a KMS behind the KEK, one call per key and day; after retirement no instance can read the
 * key's rows, whether or not it once held the key.
 */
@Component
class RetentionClassKeys(
    private val repository: DataKeyRepository,
    private val wrapping: DataKeyWrapping,
    private val toolSessionRetention: ToolSessionRetentionProperties,
    transactionManager: PlatformTransactionManager,
    private val clock: Clock,
) {
    private class Cached(val key: ByteArray, val retireAfter: Instant)

    private val cache = ConcurrentHashMap<String, Cached>()
    private val transactions = TransactionTemplate(transactionManager)
    private val log = LoggerFactory.getLogger(RetentionClassKeys::class.java)

    /** Today's key for tool working data; created on the spot if provisioning has not run. */
    fun currentToolSessionKey(): OpenDataKey {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        val keyId = keyId(today)
        return OpenDataKey(keyId, keyOf(keyId) ?: create(today))
    }

    /** The key a row names, or `null` once it is retired - that row's data is gone for good. */
    fun keyOf(keyId: String): ByteArray? {
        val now = clock.instant()
        cache[keyId]?.let { cached ->
            if (cached.retireAfter.isAfter(now)) return cached.key
            cache.remove(keyId)
        }
        val stored = repository.findById(keyId).orElse(null) ?: return null
        val retireAfter = checkNotNull(stored.retireAfter)
        if (!retireAfter.isAfter(now)) return null
        return wrapping.unwrap(WrappedDataKey(checkNotNull(stored.wrappedKey), checkNotNull(stored.kekVersion)))
            .also { cache[keyId] = Cached(it, retireAfter) }
    }

    /** Today's and tomorrow's key, so the day boundary finds its key ready on every instance. */
    @EventListener(ApplicationReadyEvent::class)
    @Scheduled(fixedDelay = 3_600_000, initialDelay = 3_600_000)
    fun provision() {
        val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        listOf(today, today.plusDays(1)).forEach { day ->
            runCatching { if (keyOf(keyId(day)) == null) transactions.executeWithoutResult { create(day) } }
                .onFailure { log.warn("Could not provision data key {}: {}", keyId(day), it.message) }
        }
    }

    /**
     * Inserts the key of [day] in the caller's transaction. Two writers of the same first row of a
     * day collide on the primary key; the loser's request fails once and finds the key on retry.
     * Provisioning keeps that to the lazy path.
     */
    private fun create(day: LocalDate): ByteArray {
        val keyId = keyId(day)
        val key = wrapping.newKey()
        val wrapped = wrapping.wrap(key)
        val retireAfter = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() + JOURNEY_RETENTION + toolSessionRetention.retention
        repository.saveAndFlush(
            DataKey(
                keyId = keyId, retentionClass = TOOL_SESSION, wrappedKey = wrapped.bytes, kekVersion = wrapped.kekVersion,
                createdAt = clock.instant(), retireAfter = retireAfter
            )
        )
        cache[keyId] = Cached(key, retireAfter)
        return key
    }

    private fun keyId(day: LocalDate) = "$TOOL_SESSION:$day"

    companion object {
        const val TOOL_SESSION = "TOOL_SESSION"
    }
}
