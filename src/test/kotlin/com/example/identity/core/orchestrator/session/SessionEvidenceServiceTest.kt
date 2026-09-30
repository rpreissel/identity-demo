package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.Subject
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import java.util.UUID

/**
 * Unit test of [SessionEvidenceService]'s cache invalidation: after a step-up no stale AccessToken may
 * stay pollable in an [AppTokenSession]. [applyEvidence] and [applyEvidenceUpdate] both change the
 * [SessionEvidenceRecord] row a token was minted from, so both clear every [AppTokenSession] pointing at it.
 */
class SessionEvidenceServiceTest : BehaviorSpec({

    fun service(
        sessionEvidenceRepository: SessionEvidenceRecordRepository,
        appTokenSessionRepository: AppTokenSessionRepository
    ) = SessionEvidenceService(sessionEvidenceRepository, appTokenSessionRepository, mockk(relaxed = true), clock = TEST_CLOCK)

    given("a step-up that adds a single tool's evidence (applyEvidence)") {
        then("clears the cached AccessToken of every AppTokenSession minted from that evidence") {
            val sessionEvidenceId = UUID.randomUUID()
            val evidence = SessionEvidenceRecord(Subject.Account(1L), TEST_NOW)
            val sessionEvidenceRepository = mockk<SessionEvidenceRecordRepository>()
            every { sessionEvidenceRepository.findById(sessionEvidenceId) } returns Optional.of(evidence)
            every { sessionEvidenceRepository.save(any()) } answers { firstArg() }

            val window = TEST_NOW.plusSeconds(1800)
            val staleContext = AppTokenSession(accountId = 1L, now = TEST_NOW).apply {
                accessToken = "stale-token"
                accessExpiresAt = TEST_NOW.plusSeconds(300)
                refreshToken = "stale-refresh"
                refreshExpiresAt = window
            }
            val appTokenSessionRepository = mockk<AppTokenSessionRepository>()
            every { appTokenSessionRepository.findBySessionEvidenceId(sessionEvidenceId) } returns listOf(staleContext)
            every { appTokenSessionRepository.save(any()) } answers { firstArg() }

            service(sessionEvidenceRepository, appTokenSessionRepository).applyEvidence(sessionEvidenceId, emptyList())

            staleContext.accessToken shouldBe null
            staleContext.accessExpiresAt shouldBe null
            staleContext.refreshToken shouldBe null
            // The session and its window stay; the next token continues it (ADR-43).
            staleContext.refreshExpiresAt shouldBe window
            verify { appTokenSessionRepository.save(staleContext) }
        }
    }

    given("no AppTokenSession was ever minted from this evidence") {
        then("is a no-op - nothing to invalidate, no pointless save") {
            val sessionEvidenceId = UUID.randomUUID()
            val evidence = SessionEvidenceRecord(Subject.Account(1L), TEST_NOW)
            val sessionEvidenceRepository = mockk<SessionEvidenceRecordRepository>()
            every { sessionEvidenceRepository.findById(sessionEvidenceId) } returns Optional.of(evidence)
            every { sessionEvidenceRepository.save(any()) } answers { firstArg() }

            val appTokenSessionRepository = mockk<AppTokenSessionRepository>()
            every { appTokenSessionRepository.findBySessionEvidenceId(sessionEvidenceId) } returns emptyList()

            service(sessionEvidenceRepository, appTokenSessionRepository).applyEvidenceUpdate(sessionEvidenceId, emptyList(), "kc")

            verify(exactly = 0) { appTokenSessionRepository.save(any()) }
        }
    }
})
