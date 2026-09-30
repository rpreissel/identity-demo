package com.example.identity.core.account.application

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.infrastructure.ChangeLogEntry
import com.example.identity.core.account.infrastructure.ChangeLogRepository
import com.example.identity.core.account.infrastructure.ChangeType
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Pageable
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/** Why a method stopped being active. */
enum class MethodDeactivationReason {
    /** A singleton method was enrolled again; the new instance replaces the old one. */
    REPLACED,

    /** The account holder removed it (MANAGE_METHODS). */
    REMOVED_BY_HOLDER,
}

/**
 * The one way to write the change log. One function per event fixes the keys an event carries.
 * Joins the caller's transaction, so an event exists exactly when its change does. Internal,
 * because only the account itself changes the account.
 */
@Component
class ChangeLog(private val repository: ChangeLogRepository, private val clock: Clock) {

    /**
     * An identification run. Only the named references of the tool's report are kept: where to ask,
     * which procedure and version, a hash of what was seen. Anything else is dropped, above all a
     * document number, which may not be kept (§ 20 PAuswG).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun identified(
        accountId: AccountId, method: String, acr: String?, role: String?, report: Map<String, Any?>,
        lookupKey: LookupKey?, personId: PartnerNumber?,
    ) =
        record(
            accountId, ChangeType.IDENTIFIED, subject = method, acr = acr,
            details = mapOf("role" to role) + IDENTIFICATION_REFERENCE_KEYS.associateWith { report[it]?.toString() },
            lookupKey = lookupKey?.value, lookupKeyId = lookupKey?.keyId, personId = personId,
        )

    /** A method was added: under which proofs of the session (amr) and on which channel. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun methodAdded(accountId: AccountId, method: String, acr: String?, amr: List<String>, channel: String?, at: Instant) =
        record(accountId, ChangeType.METHOD_ADDED, subject = method, acr = acr, at = at, details = mapOf("amr" to amr, "channel" to channel))

    @Transactional(propagation = Propagation.MANDATORY)
    fun methodDeactivated(accountId: AccountId, method: String?, reason: MethodDeactivationReason, at: Instant) =
        record(accountId, ChangeType.METHOD_DEACTIVATED, subject = method, at = at, details = mapOf("reason" to reason.name))

    /** An attribute was withdrawn - by whom (trust anchor) and why, never its value. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun attributeRetracted(accountId: AccountId, attributeType: String?, retractionSource: String?, reason: String?, at: Instant) =
        record(
            accountId, ChangeType.ATTRIBUTE_RETRACTED, subject = attributeType, at = at,
            details = mapOf("retractionSource" to retractionSource, "reason" to reason),
        )

    @Transactional(propagation = Propagation.MANDATORY)
    fun accountDeleted(accountId: AccountId) = record(accountId, ChangeType.ACCOUNT_DELETED)

    /**
     * [into] took over the disposable account [from] (ADR-20), including its identifications.
     * Copied, not moved: the trail stays append-only, and each copy names where it came from.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun accountAbsorbed(into: AccountId, from: AccountId) {
        repository.findByAccountIdAndChangeTypeOrderByOccurredAt(from, ChangeType.IDENTIFIED).forEach { e ->
            // Keeps the version the original was written with - its keys are that version's.
            repository.save(
                ChangeLogEntry(
                    accountId = into, changeType = ChangeType.IDENTIFIED, subject = e.subject, acr = e.acr,
                    details = e.details.orEmpty() + mapOf("carriedFromAccountId" to from), occurredAt = e.occurredAt,
                    lookupKey = e.lookupKey, personId = e.personId,
                )
            )
        }
        record(into, ChangeType.ACCOUNT_ABSORBED, details = mapOf("absorbedAccountId" to from))
    }

    private fun record(
        accountId: AccountId, type: ChangeType, subject: String? = null, acr: String? = null,
        at: Instant? = null, details: Map<String, Any?> = emptyMap(),
        lookupKey: String? = null, lookupKeyId: String? = null, personId: PartnerNumber? = null,
    ) {
        repository.save(
            ChangeLogEntry(
                accountId = accountId, changeType = type, subject = subject, acr = acr,
                details = mapOf("type" to type.name, "version" to type.detailsVersion) + details.filterValues { it != null },
                occurredAt = at ?: clock.instant(), lookupKey = lookupKey, lookupKeyId = lookupKeyId, personId = personId,
            )
        )
    }

    private companion object {
        /** The only keys of an identification report that reach the trail (see [identified]). */
        val IDENTIFICATION_REFERENCE_KEYS = listOf("provider", "providerTxId", "procedure", "methodVersion", "evidenceHash")
    }
}

/**
 * Deletes the change log of accounts deleted longer than `account.change-log.retention-years` ago
 * (ADR-39). In batches with one transaction each, since a year's deletions can be many rows.
 */
@Component
class ChangeLogRetention(
    private val repository: ChangeLogRepository,
    private val transactions: TransactionTemplate,
    private val meterRegistry: MeterRegistry,
    @Value("\${account.change-log.retention-years:10}") private val retentionYears: Long,
    private val clock: Clock,
) {
    @Scheduled(fixedDelay = 86_400_000, initialDelay = 300_000)
    fun sweep() {
        val deleted = purge(clock.instant())
        meterRegistry.counter("identity.retention.deleted", "table", "change_log").increment(deleted.toDouble())
    }

    fun purge(now: Instant): Int {
        val cutoff = now.atZone(java.time.ZoneOffset.UTC).minusYears(retentionYears).toInstant()
        var total = 0
        while (true) {
            val deleted = transactions.execute {
                val accounts = repository.accountsDeletedBefore(cutoff, Pageable.ofSize(BATCH))
                if (accounts.isEmpty()) 0 else repository.deleteByAccountIdIn(accounts)
            }
            if (deleted == 0) return total
            total += deleted
        }
    }

    private companion object {
        const val BATCH = 500
    }
}
