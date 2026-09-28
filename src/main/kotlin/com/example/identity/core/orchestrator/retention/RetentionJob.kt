package com.example.identity.core.orchestrator.retention

import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.core.account.AccountService
import io.micrometer.core.instrument.MeterRegistry
import com.example.identity.core.orchestrator.journey.AuthJourneyRepository
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceRepository
import com.example.identity.core.orchestrator.session.AttemptThrottleRepository
import com.example.identity.core.orchestrator.session.AuthContextRepository
import com.example.identity.core.orchestrator.session.EvidenceTrailRepository
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
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
 * from the inside out (ToolSession, AuthJourney, ChannelSession with AuthContext and AuthEvidence),
 * so no row outlives its FK target. Journey trace and attempt throttle are swept by age only; nothing
 * else bounds them. Of the account data only abandoned registrations go (ADR-46). This job makes no
 * network calls.
 */
@Component
class RetentionJob(
    private val toolSessionRepository: ToolSessionRepository,
    private val journeyRepository: AuthJourneyRepository,
    private val channelSessionRepository: ChannelSessionRepository,
    private val authContextRepository: AuthContextRepository,
    private val authEvidenceRepository: EvidenceTrailRepository,
    private val journeyTraceRepository: JourneyTraceRepository,
    private val attemptThrottleRepository: AttemptThrottleRepository,
    private val accountService: AccountService,
    private val accountDeletionService: AccountDeletionService,
    private val meterRegistry: MeterRegistry,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(RetentionJob::class.java)

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    @Transactional
    fun cleanup() {
        val now = clock.instant()
        toolSessionRepository.deleteByExpiresAtBefore(now.minus(TOOL_SESSION_RETENTION))
        deleteExpiredJourneys(now.minus(JOURNEY_RETENTION))
        deleteExpiredChannels(now.minus(CHANNEL_SESSION_RETENTION))
        discardAbandonedRegistrations(now)

        val journeyTraceEntries = journeyTraceRepository.deleteByCreatedAtBefore(now.minus(JOURNEY_TRACE_RETENTION))
        val staleCounters = attemptThrottleRepository.deleteStaleCounters(now.minus(ATTEMPT_THROTTLE_RETENTION), now)
        countDeleted("journey_trace", journeyTraceEntries)
        countDeleted("attempt_throttle", staleCounters)
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
            journeyRepository.deleteAllByIdInBatch(batch)
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
        val orphanedAuthContextIds = channels.mapNotNull { it.authContextId }
        val orphanedAuthEvidenceIds = channels.mapNotNull { it.authEvidenceId }
        val channelSessionIds = channels.mapNotNull { it.channelSessionId }
        if (channelSessionIds.isNotEmpty()) {
            val journeyIds = journeyRepository.findIdsByChannelSessionIdIn(channelSessionIds)
            if (journeyIds.isNotEmpty()) {
                toolSessionRepository.deleteByJourneyIdIn(journeyIds)
                journeyRepository.deleteAllByIdInBatch(journeyIds)
            }
        }
        // One statement per table and batch; deleteAll would delete row by row.
        channelSessionRepository.deleteAllInBatch(channels)
        if (orphanedAuthContextIds.isNotEmpty()) {
            authContextRepository.deleteAllByIdInBatch(orphanedAuthContextIds)
        }
        if (orphanedAuthEvidenceIds.isNotEmpty()) {
            authEvidenceRepository.deleteAllByIdInBatch(orphanedAuthEvidenceIds)
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

        private val TOOL_SESSION_RETENTION: Duration = Duration.ofHours(24)
        private val JOURNEY_RETENTION: Duration = Duration.ofDays(7)
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
         * Far beyond the longest throttle window or lockout (15 minutes), so a sweep never shortens
         * an active budget. [AttemptThrottleRepository.deleteStaleCounters] also skips running locks.
         */
        private val ATTEMPT_THROTTLE_RETENTION: Duration = Duration.ofDays(7)
    }
}
