package com.example.identity.core.orchestrator.retention

import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.account.JourneyKeys
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.core.account.AccountService
import io.micrometer.core.instrument.MeterRegistry
import com.example.identity.core.orchestrator.journey.AuthJourneyRepository
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceRepository
import com.example.identity.core.orchestrator.session.RateLimitRecordRepository
import com.example.identity.core.orchestrator.session.AppTokenSessionRepository
import com.example.identity.core.orchestrator.session.KeycloakSessionEvidenceRepository
import com.example.identity.core.orchestrator.session.SessionEvidenceRecordRepository
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
import com.example.identity.core.orchestrator.session.DataKeyRepository
import com.example.identity.core.orchestrator.session.JOURNEY_RETENTION
import com.example.identity.core.orchestrator.session.ToolSessionRetentionProperties
import com.example.identity.core.orchestrator.session.ToolSessionRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Retention for the orchestrator's session data (docs/07-betrieb.md #3), in one transaction. Cleans
 * from the inside out (ToolSession, AuthJourney, ChannelSession with AppTokenSession and SessionEvidence),
 * so no row outlives its FK target. Journey trace and attempt rate limit are swept by age only; nothing
 * else bounds them. Of the account data only abandoned registrations go (ADR-46). This job makes no
 * network calls.
 */
@Component
class RetentionJob(
    private val toolSessionRepository: ToolSessionRepository,
    private val journeyRepository: AuthJourneyRepository,
    private val channelSessionRepository: ChannelSessionRepository,
    private val appTokenSessionRepository: AppTokenSessionRepository,
    private val sessionEvidenceRepository: SessionEvidenceRecordRepository,
    private val keycloakSessionEvidenceRepository: KeycloakSessionEvidenceRepository,
    private val journeyTraceRepository: JourneyTraceRepository,
    private val rateLimitRecordRepository: RateLimitRecordRepository,
    private val dataKeyRepository: DataKeyRepository,
    private val accountService: AccountService,
    private val journeyKeys: JourneyKeys,
    private val accountDeletionService: AccountDeletionService,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
    /** How long a tool session, with its tool's working data (ADR-49), outlives its expiry. */
    private val toolSessionRetention: ToolSessionRetentionProperties = ToolSessionRetentionProperties(),
) {
    private val log = LoggerFactory.getLogger(RetentionJob::class.java)

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val now = clock.instant()
        toolSessionRepository.deleteByExpiresAtBefore(now.minus(toolSessionRetention.retention))
        deleteExpiredJourneys(now.minus(JOURNEY_RETENTION))
        deleteExpiredChannels(now.minus(CHANNEL_SESSION_RETENTION))
        discardAbandonedRegistrations(now)

        val journeyTraceEntries = journeyTraceRepository.deleteByCreatedAtBefore(now.minus(JOURNEY_TRACE_RETENTION))
        val staleCounters = rateLimitRecordRepository.deleteStaleCounters(now.minus(RATE_LIMIT_RETENTION), now)
        // Keys of days whose rows are all gone (ADR-53): what still lies under them is unreadable now.
        countDeleted("data_key", dataKeyRepository.deleteByRetireAfterBefore(now))
        // Journey keys no account adopted (ADR-55): the channels that could name them are gone by now.
        countDeleted("master_key", journeyKeys.deleteJourneyKeysCreatedBefore(now.minus(CHANNEL_SESSION_RETENTION)))
        // What ended Keycloak sessions proved (ADR-59): their end is the rows' expiry.
        countDeleted("keycloak_session_evidence", keycloakSessionEvidenceRepository.deleteExpired(now))
        countDeleted("journey_trace", journeyTraceEntries)
        countDeleted("rate_limit", staleCounters)
        if (journeyTraceEntries > 0 || staleCounters > 0) {
            log.info(
                "Retention: deleted {} journey trace entry/entries and {} attempt throttle counter(s)",
                journeyTraceEntries,
                staleCounters
            )
        }
    }

    /**
     * Deletes due journeys in fixed-size batches, so no unbounded `IN` list is built. Each batch is
     * deleted before the next query, so page 0 is always the remaining backlog.
     */
    private fun deleteExpiredJourneys(cutoff: Instant) {
        var batch = journeyRepository.findIdsForRetention(cutoff, PageRequest.of(0, RETENTION_BATCH_SIZE))
        while (batch.isNotEmpty()) {
            toolSessionRepository.deleteByJourneyIdIn(batch)
            journeyRepository.deleteAllByJourneyIdInBatch(batch)
            batch = journeyRepository.findIdsForRetention(cutoff, PageRequest.of(0, RETENTION_BATCH_SIZE))
        }
    }

    /** Same batching as [deleteExpiredJourneys], for channel sessions. */
    private fun deleteExpiredChannels(cutoff: Instant) {
        var batch = channelSessionRepository.findByExpiresAtBefore(cutoff, PageRequest.of(0, RETENTION_BATCH_SIZE))
        while (batch.isNotEmpty()) {
            deleteChannels(batch)
            batch = channelSessionRepository.findByExpiresAtBefore(cutoff, PageRequest.of(0, RETENTION_BATCH_SIZE))
        }
    }

    private fun deleteChannels(channels: List<ChannelSession>) {
        if (channels.isEmpty()) return
        val orphanedAppTokenSessionIds = channels.mapNotNull { it.appTokenSessionId }
        val orphanedSessionEvidenceIds = channels.mapNotNull { it.sessionEvidenceId }
        val channelSessionIds = channels.mapNotNull { it.channelSessionId }
        if (channelSessionIds.isNotEmpty()) {
            val journeyIds = journeyRepository.findIdsByChannelSessionIdIn(channelSessionIds)
            if (journeyIds.isNotEmpty()) {
                toolSessionRepository.deleteByJourneyIdIn(journeyIds)
                journeyRepository.deleteAllByJourneyIdInBatch(journeyIds)
            }
        }
        // One statement per table and batch; deleteAll would delete row by row.
        channelSessionRepository.deleteAllInBatch(channels)
        if (orphanedAppTokenSessionIds.isNotEmpty()) {
            appTokenSessionRepository.deleteAllByIdInBatch(orphanedAppTokenSessionIds)
        }
        if (orphanedSessionEvidenceIds.isNotEmpty()) {
            sessionEvidenceRepository.deleteAllBySessionEvidenceIdInBatch(orphanedSessionEvidenceIds)
        }
        countDeleted("channel_session", channels.size)
        log.info("Retention: deleted {} channel session(s)", channels.size)
    }

    /**
     * Accounts still being set up that no live channel works with any more (ADR-46): their
     * registration ended without a cancel, e.g. the app was closed. A cancel discards its account
     * itself; this catches the rest, oldest first, one batch per run.
     */
    private fun discardAbandonedRegistrations(now: Instant) {
        val abandoned = accountService.accountsBeingSetUpCreatedBefore(now.minus(REGISTRATION_GRACE), RETENTION_BATCH_SIZE)
            .filterNot { channelSessionRepository.existsByAccountIdAndStateNotInAndExpiresAtAfter(it, TERMINAL_STATES, now) }
        abandoned.forEach { accountDeletionService.deleteAccount(it) }
        if (abandoned.isNotEmpty()) {
            countDeleted("account_being_set_up", abandoned.size)
            log.info("Retention: discarded {} abandoned registration(s)", abandoned.size)
        }
    }

    /** `identity.retention.deleted` by table (docs/07-betrieb.md Abschnitt 7): a sweep that stops deleting shows as a flat line. */
    private fun countDeleted(table: String, rows: Int) {
        meterRegistry.counter(RETENTION_METRIC, "table", table).increment(rows.toDouble())
    }

    companion object {
        const val RETENTION_METRIC = "identity.retention.deleted"

        /** Page size for [deleteExpiredJourneys] and [deleteExpiredChannels]. */
        private const val RETENTION_BATCH_SIZE = 500

        /** An account being set up younger than this is never discarded, whatever the channels say. */
        private val REGISTRATION_GRACE: Duration = Duration.ofHours(1)
        private val TERMINAL_STATES = ChannelState.entries.filter { it.isTerminal }
        /** As long as [JOURNEY_TRACE_RETENTION], which is read per channel session, and no longer. */
        private val CHANNEL_SESSION_RETENTION: Duration = Duration.ofDays(14)

        /**
         * The highest-volume table, one row per journey step. It is a debugging trace, not the
         * change log (`account.change_log`, ADR-39), so it need not outlive the channel sessions.
         */
        private val JOURNEY_TRACE_RETENTION: Duration = Duration.ofDays(14)

        /**
         * Far beyond the longest rate limit window or lockout (15 minutes), so a sweep never shortens
         * an active budget. [RateLimitRecordRepository.deleteStaleCounters] also skips running locks.
         */
        private val RATE_LIMIT_RETENTION: Duration = Duration.ofDays(7)
    }
}
