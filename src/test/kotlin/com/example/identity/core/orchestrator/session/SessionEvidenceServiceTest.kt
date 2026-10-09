package com.example.identity.core.orchestrator.session

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.UUID

/**
 * Unit test of [SessionEvidenceService]'s cache invalidation: after a step-up no stale AccessToken may
 * stay pollable in an [AppTokenSession]. [SessionEvidenceService.applyEvidence] changes the
 * [SessionEvidenceRecord] row a token was minted from, so it clears every [AppTokenSession] pointing at it.
 */
class SessionEvidenceServiceTest : BehaviorSpec({

    val sessionEvidenceId = SessionEvidenceId(UUID.randomUUID())
    val refreshWindow = TEST_NOW.plusSeconds(1800)

    given("evidence with an AppTokenSession that holds cached tokens") {
        `when`("a single tool's evidence is added (applyEvidence)") {
            val fixture = SessionEvidenceFixture(sessionEvidenceId, refreshWindow)
            fixture.service.applyEvidence(sessionEvidenceId, emptyList())

            then("the cached tokens are cleared and saved") {
                fixture.cachedTokensAreCleared()
            }

            then("the session and its window stay; the next token continues it (ADR-43)") {
                fixture.appTokenSession!!.refreshExpiresAt shouldBe refreshWindow
            }
        }

    }

    given("evidence no AppTokenSession was ever minted from") {
        `when`("evidence is added") {
            val fixture = SessionEvidenceFixture(sessionEvidenceId, refreshWindow = null)
            fixture.service.applyEvidence(sessionEvidenceId, emptyList())

            then("nothing is saved - nothing to invalidate") {
                verify(exactly = 0) { fixture.appTokenSessionRepository.save(any()) }
            }
        }
    }
})

/** The service over [sessionEvidenceId]'s evidence; with a [refreshWindow], one AppTokenSession holds cached tokens. */
private class SessionEvidenceFixture(sessionEvidenceId: SessionEvidenceId, refreshWindow: Instant?) {
    private val sessionEvidenceRepository = mockk<SessionEvidenceRecordRepository>()
    val appTokenSessionRepository = mockk<AppTokenSessionRepository>()
    val appTokenSession = refreshWindow?.let { window ->
        AppTokenSession(accountId = AccountId(1L), now = TEST_NOW).apply {
            accessToken = "stale-token"
            accessExpiresAt = TEST_NOW.plusSeconds(300)
            refreshToken = "stale-refresh"
            refreshExpiresAt = window
        }
    }
    val service = SessionEvidenceService(sessionEvidenceRepository, appTokenSessionRepository, mockk(relaxed = true), clock = TEST_CLOCK)

    init {
        every { sessionEvidenceRepository.findBySessionEvidenceId(sessionEvidenceId) } returns
            SessionEvidenceRecord(Subject.Account(AccountId(1L)), TEST_NOW)
        every { sessionEvidenceRepository.save(any()) } answers { firstArg() }
        every { appTokenSessionRepository.findBySessionEvidenceId(sessionEvidenceId) } returns listOfNotNull(appTokenSession)
        every { appTokenSessionRepository.save(any()) } answers { firstArg() }
    }

    fun cachedTokensAreCleared() {
        val session = checkNotNull(appTokenSession)
        session.accessToken.shouldBeNull()
        session.accessExpiresAt.shouldBeNull()
        session.refreshToken.shouldBeNull()
        verify { appTokenSessionRepository.save(session) }
    }
}
