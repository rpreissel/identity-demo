package com.example.identity.core.orchestrator.retention

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.core.account.AccountService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.example.identity.core.orchestrator.session.RateLimitRecordRepository
import com.example.identity.core.orchestrator.session.AppTokenSessionRepository
import com.example.identity.core.orchestrator.session.SessionEvidenceRecordRepository
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.ChannelSessionRepository

import com.example.identity.core.orchestrator.journey.AuthJourneyRepository
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Unit test of [RetentionJob]: expired [ChannelSession]s' `appTokenSessionId`s are collected before
 * those channels are deleted, and passed on only when there are any. Otherwise retention would
 * orphan rows or crash. Repositories are mocked; every assertion is about what gets deleted.
 */
class RetentionJobTest : BehaviorSpec({

    fun job(
        channelSessionRepository: ChannelSessionRepository,
        appTokenSessionRepository: AppTokenSessionRepository = mockk(relaxed = true),
        sessionEvidenceRepository: SessionEvidenceRecordRepository = mockk(relaxed = true),
        journeyTraceRepository: JourneyTraceRepository = mockk(relaxed = true),
        rateLimitRecordRepository: RateLimitRecordRepository = mockk(relaxed = true),
        accountService: AccountService = mockk(relaxed = true),
        accountDeletionService: AccountDeletionService = mockk(relaxed = true),
    ) = RetentionJob(
        toolSessionRepository = mockk(relaxed = true),
        journeyRepository = mockk<AuthJourneyRepository>(relaxed = true),
        channelSessionRepository = channelSessionRepository,
        appTokenSessionRepository = appTokenSessionRepository,
        sessionEvidenceRepository = sessionEvidenceRepository,
        journeyTraceRepository = journeyTraceRepository,
        rateLimitRecordRepository = rateLimitRecordRepository,
        accountService = accountService,
        accountDeletionService = accountDeletionService,
        meterRegistry = SimpleMeterRegistry(),
        clock = TEST_CLOCK,
    )

    given("expired channels that each carry an AppTokenSession") {
        then("their appTokenSessionIds are deleted too - collected before the channels themselves are gone") {
            val appTokenSessionId1 = UUID.randomUUID()
            val appTokenSessionId2 = UUID.randomUUID()
            val expired = listOf(
                ChannelSession(now = TEST_NOW).apply { appTokenSessionId = appTokenSessionId1 },
                ChannelSession(now = TEST_NOW).apply { appTokenSessionId = appTokenSessionId2 }
            )
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returnsMany listOf(expired, emptyList())
            val appTokenSessionRepository = mockk<AppTokenSessionRepository>(relaxed = true)

            job(channelSessionRepository, appTokenSessionRepository).cleanup()

            val idsSlot = slot<List<UUID>>()
            verify { appTokenSessionRepository.deleteAllByIdInBatch(capture(idsSlot)) }
            idsSlot.captured shouldContainExactlyInAnyOrder listOf(appTokenSessionId1, appTokenSessionId2)
            verify { channelSessionRepository.deleteAllInBatch(expired) }
        }
    }

    given("expired channels with no AppTokenSession at all") {
        then("deleteAllById is never called - nothing to orphan, no pointless empty-list call") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returnsMany
                listOf(listOf(ChannelSession(now = TEST_NOW).apply { appTokenSessionId = null }), emptyList())
            val appTokenSessionRepository = mockk<AppTokenSessionRepository>(relaxed = true)

            job(channelSessionRepository, appTokenSessionRepository).cleanup()

            verify(exactly = 0) { appTokenSessionRepository.deleteAllByIdInBatch(any()) }
        }
    }

    given("no expired channels at all") {
        then("deleteAllById is never called - no orphaned AppTokenSession to name") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            val appTokenSessionRepository = mockk<AppTokenSessionRepository>(relaxed = true)

            job(channelSessionRepository, appTokenSessionRepository).cleanup()

            verify(exactly = 0) { appTokenSessionRepository.deleteAllByIdInBatch(any()) }
        }
    }

    given("the channel-session retention cutoff") {
        then("is roughly 14 days in the past - as long as the journey trace it serves, not e.g. days-vs-hours confused with another repository's window") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            val cutoffSlot = slot<Instant>()

            job(channelSessionRepository).cleanup()

            verify { channelSessionRepository.findByExpiresAtBefore(capture(cutoffSlot), any()) }
            val expected = TEST_NOW.minus(Duration.ofDays(14))
            val drift = Duration.between(cutoffSlot.captured, expected).abs()
            (drift < Duration.ofMinutes(1)) shouldBe true
        }
    }

    given("a retention run over the two age-swept tables that hang off no foreign key") {
        then("the journey trace is swept by age, and stale throttle counters with it (B3)") {
            val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
            every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
            val journeyTraceRepository = mockk<JourneyTraceRepository>(relaxed = true)
            val rateLimitRecordRepository = mockk<RateLimitRecordRepository>(relaxed = true)
            val logCutoff = slot<Instant>()
            val rateLimitCutoff = slot<Instant>()
            val rateLimitNow = slot<Instant>()
            every { journeyTraceRepository.deleteByCreatedAtBefore(capture(logCutoff)) } returns 0
            every { rateLimitRecordRepository.deleteStaleCounters(capture(rateLimitCutoff), capture(rateLimitNow)) } returns 0

            val before = TEST_NOW
            job(
                channelSessionRepository,
                journeyTraceRepository = journeyTraceRepository,
                rateLimitRecordRepository = rateLimitRecordRepository
            ).cleanup()

            // Both cutoffs are ages, not "now". A sweep with the current instant would also delete
            // counters whose lock still runs.
            logCutoff.captured shouldBeLessThan before.minus(Duration.ofDays(13))
            rateLimitCutoff.captured shouldBeLessThan before.minus(Duration.ofDays(6))
            // The longest lockout any rate limit service uses is 15 minutes; the retention window
            // must stay far beyond it so a sweep can never shorten an active budget.
            rateLimitCutoff.captured shouldBeLessThan rateLimitNow.captured.minus(Duration.ofHours(1))
        }
    }

    given("two accounts still being set up, one of them still used by a live channel (ADR-46)") {
        val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true)
        every { channelSessionRepository.findByExpiresAtBefore(any(), any()) } returns emptyList()
        every { channelSessionRepository.existsByAccountIdAndStateNotInAndExpiresAtAfter(1L, any(), any()) } returns true
        every { channelSessionRepository.existsByAccountIdAndStateNotInAndExpiresAtAfter(2L, any(), any()) } returns false
        val accountService = mockk<AccountService>(relaxed = true)
        every { accountService.accountsBeingSetUpCreatedBefore(any(), any()) } returns listOf(1L, 2L)
        val accountDeletionService = mockk<AccountDeletionService>(relaxed = true)

        `when`("the job runs") {
            job(channelSessionRepository, accountService = accountService, accountDeletionService = accountDeletionService).cleanup()

            then("only the abandoned one is discarded, as a whole") {
                verify(exactly = 1) { accountDeletionService.deleteAccount(2L) }
                verify(exactly = 0) { accountDeletionService.deleteAccount(1L) }
            }
        }
    }
})
