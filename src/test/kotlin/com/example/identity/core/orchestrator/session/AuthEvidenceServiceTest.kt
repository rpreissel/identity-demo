package com.example.identity.core.orchestrator.session

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Unit test of [AuthEvidenceService]'s cache invalidation: after a step-up no stale AccessToken may
 * stay pollable in an [AuthContext]. [applyEvidence] and [applyEvidenceUpdate] both change the
 * [EvidenceTrail] row a token was minted from, so both clear every [AuthContext] pointing at it.
 */
class AuthEvidenceServiceTest : BehaviorSpec({

    fun service(
        authEvidenceRepository: EvidenceTrailRepository,
        authContextRepository: AuthContextRepository
    ) = AuthEvidenceService(authEvidenceRepository, authContextRepository, mockk(relaxed = true), clock = Clock.systemUTC())

    given("a step-up that adds a single tool's evidence (applyEvidence)") {
        then("clears the cached AccessToken of every AuthContext minted from that evidence") {
            val authEvidenceId = UUID.randomUUID()
            val evidence = EvidenceTrail(accountId = 1L, now = Instant.now())
            val authEvidenceRepository = mockk<EvidenceTrailRepository>()
            every { authEvidenceRepository.findById(authEvidenceId) } returns Optional.of(evidence)
            every { authEvidenceRepository.save(any()) } answers { firstArg() }

            val window = Instant.now().plusSeconds(1800)
            val staleContext = AuthContext(accountId = 1L, now = Instant.now()).apply {
                accessToken = "stale-token"
                accessExpiresAt = Instant.now().plusSeconds(300)
                refreshToken = "stale-refresh"
                refreshExpiresAt = window
            }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findByAuthEvidenceId(authEvidenceId) } returns listOf(staleContext)
            every { authContextRepository.save(any()) } answers { firstArg() }

            service(authEvidenceRepository, authContextRepository).applyEvidence(authEvidenceId, emptyList())

            staleContext.accessToken shouldBe null
            staleContext.accessExpiresAt shouldBe null
            staleContext.refreshToken shouldBe null
            // The session and its window stay; the next token continues it (ADR-43).
            staleContext.refreshExpiresAt shouldBe window
            verify { authContextRepository.save(staleContext) }
        }
    }

    given("no AuthContext was ever minted from this evidence") {
        then("is a no-op - nothing to invalidate, no pointless save") {
            val authEvidenceId = UUID.randomUUID()
            val evidence = EvidenceTrail(accountId = 1L, now = Instant.now())
            val authEvidenceRepository = mockk<EvidenceTrailRepository>()
            every { authEvidenceRepository.findById(authEvidenceId) } returns Optional.of(evidence)
            every { authEvidenceRepository.save(any()) } answers { firstArg() }

            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findByAuthEvidenceId(authEvidenceId) } returns emptyList()

            service(authEvidenceRepository, authContextRepository).applyEvidenceUpdate(authEvidenceId, emptyList(), "kc")

            verify(exactly = 0) { authContextRepository.save(any()) }
        }
    }
})
