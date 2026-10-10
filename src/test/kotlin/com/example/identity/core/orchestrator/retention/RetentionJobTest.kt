package com.example.identity.core.orchestrator.retention

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.journey.AuthJourneyRepository
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceRepository
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.core.orchestrator.session.AppTokenSessionRepository
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
import com.example.identity.core.orchestrator.session.RateLimitRecordRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** How long the journey trace is kept; channel sessions are kept exactly as long, since it is read per channel session. */
private val JOURNEY_TRACE_RETENTION = Duration.ofDays(14)

/** How long a throttle counter outlives its last use. */
private val RATE_LIMIT_RETENTION = Duration.ofDays(7)

/** The longest lockout any rate limit service uses. */
private val LONGEST_LOCKOUT = Duration.ofMinutes(15)

/**
 * Unit test of [RetentionJob]: expired [ChannelSession]s' `appTokenSessionId`s are collected before
 * those channels are deleted, and passed on only when there are any. Otherwise retention would
 * orphan rows or crash. Repositories are mocked; every assertion is about what gets deleted.
 */
class RetentionJobTest : BehaviorSpec({

    given("expired channels that each carry an AppTokenSession") {
        val fixture = RetentionFixture()
        val appTokenSessionIds = listOf(UUID.randomUUID(), UUID.randomUUID())
        val expired = appTokenSessionIds.map { id -> ChannelSession(now = TEST_NOW).apply { appTokenSessionId = id } }
        every { fixture.channelSessionRepository.findByExpiresAtBefore(any(), any()) } returnsMany listOf(expired, emptyList())
        val deletedIds = slot<List<UUID>>()
        every { fixture.appTokenSessionRepository.deleteAllByIdInBatch(capture(deletedIds)) } returns Unit

        `when`("the job runs") {
            fixture.job.cleanup()

            then("their appTokenSessionIds are deleted too - collected before the channels themselves are gone") {
                deletedIds.captured shouldContainExactlyInAnyOrder appTokenSessionIds
                verify { fixture.channelSessionRepository.deleteAllInBatch(expired) }
            }
        }
    }

    given("expired channels with no AppTokenSession at all") {
        val fixture = RetentionFixture()
        every { fixture.channelSessionRepository.findByExpiresAtBefore(any(), any()) } returnsMany
            listOf(listOf(ChannelSession(now = TEST_NOW).apply { appTokenSessionId = null }), emptyList())

        `when`("the job runs") {
            fixture.job.cleanup()

            then("deleteAllById is never called - nothing to orphan, no pointless empty-list call") {
                verify(exactly = 0) { fixture.appTokenSessionRepository.deleteAllByIdInBatch(any()) }
            }
        }
    }

    given("no expired channels at all") {
        val fixture = RetentionFixture()
        val channelCutoff = slot<Instant>()
        every { fixture.channelSessionRepository.findByExpiresAtBefore(capture(channelCutoff), any()) } returns emptyList()
        val traceCutoff = slot<Instant>()
        every { fixture.journeyTraceRepository.deleteByCreatedAtBefore(capture(traceCutoff)) } returns 0
        val counterCutoff = slot<Instant>()
        val counterNow = slot<Instant>()
        every { fixture.rateLimitRecordRepository.deleteStaleCounters(capture(counterCutoff), capture(counterNow)) } returns 0

        `when`("the job runs") {
            fixture.job.cleanup()

            then("deleteAllById is never called - no orphaned AppTokenSession to name") {
                verify(exactly = 0) { fixture.appTokenSessionRepository.deleteAllByIdInBatch(any()) }
            }

            then("channel sessions are kept exactly as long as the journey trace they serve") {
                channelCutoff.captured shouldBe TEST_NOW.minus(JOURNEY_TRACE_RETENTION)
            }

            then("the journey trace, which hangs off no foreign key, is swept by age (B3)") {
                traceCutoff.captured shouldBe TEST_NOW.minus(JOURNEY_TRACE_RETENTION)
            }

            then("stale throttle counters are swept by age, not by now - a lock that still runs survives") {
                counterNow.captured shouldBe TEST_NOW
                counterCutoff.captured shouldBe TEST_NOW.minus(RATE_LIMIT_RETENTION)
                counterCutoff.captured shouldBeLessThan counterNow.captured.minus(LONGEST_LOCKOUT)
            }
        }
    }

    given("two accounts still being set up, one of them still used by a live channel (ADR-46)") {
        val fixture = RetentionFixture()
        every { fixture.channelSessionRepository.existsByAccountIdAndStateNotInAndExpiresAtAfter(AccountId(1L), any(), any()) } returns true
        every { fixture.channelSessionRepository.existsByAccountIdAndStateNotInAndExpiresAtAfter(AccountId(2L), any(), any()) } returns false
        every { fixture.accountService.accountsBeingSetUpCreatedBefore(any(), any()) } returns listOf(AccountId(1), AccountId(2))

        `when`("the job runs") {
            fixture.job.cleanup()

            then("only the abandoned one is discarded, as a whole") {
                verify(exactly = 1) { fixture.accountDeletionService.deleteAccount(AccountId(2L)) }
                verify(exactly = 0) { fixture.accountDeletionService.deleteAccount(AccountId(1L)) }
            }
        }
    }
})

/** The job over relaxed repositories; no channel has expired unless a `given` says so. */
private class RetentionFixture {
    val channelSessionRepository = mockk<ChannelSessionRepository>(relaxed = true) {
        every { findByExpiresAtBefore(any(), any()) } returns emptyList()
    }
    val appTokenSessionRepository = mockk<AppTokenSessionRepository>(relaxed = true)
    val journeyTraceRepository = mockk<JourneyTraceRepository>(relaxed = true)
    val rateLimitRecordRepository = mockk<RateLimitRecordRepository>(relaxed = true)
    val accountService = mockk<AccountService>(relaxed = true)
    val accountDeletionService = mockk<AccountDeletionService>(relaxed = true)
    val job = RetentionJob(
        toolSessionRepository = mockk(relaxed = true),
        journeyRepository = mockk<AuthJourneyRepository>(relaxed = true),
        channelSessionRepository = channelSessionRepository,
        appTokenSessionRepository = appTokenSessionRepository,
        sessionEvidenceRepository = mockk(relaxed = true),
        keycloakSessionEvidenceRepository = mockk(relaxed = true),
        journeyTraceRepository = journeyTraceRepository,
        rateLimitRecordRepository = rateLimitRecordRepository,
        dataKeyRepository = mockk(relaxed = true),
        accountService = accountService,
        journeyKeys = mockk(relaxed = true),
        accountDeletionService = accountDeletionService,
        meterRegistry = SimpleMeterRegistry(),
        clock = TEST_CLOCK,
    )
}
