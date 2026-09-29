package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
@Transactional
class AuthEvidenceService(
    private val authEvidenceRepository: EvidenceTrailRepository,
    private val authContextRepository: AuthContextRepository,
    private val sessionManagementService: SessionManagementService,
    private val clock: Clock
) {

    fun createForAccount(accountId: Long): EvidenceTrail =
        authEvidenceRepository.save(EvidenceTrail(Subject.Account(accountId), clock.instant()))

    /** The evidence of a channel signed in with a one-time password: it belongs to the invitation. */
    fun createForInvitation(invitation: String): EvidenceTrail =
        authEvidenceRepository.save(EvidenceTrail(Subject.Invitation(invitation), clock.instant()))

    fun getAuthEvidence(authEvidenceId: UUID): EvidenceTrail? =
        authEvidenceRepository.findByIdOrNull(authEvidenceId)

    /**
     * Re-points an evidence trail and its token contexts at another account (ADR-20), when an
     * assignment resolves a different account than the provisional one in hand. The trail is not
     * reset: what this session proved still counts. The cached tokens were minted for the yielding
     * account and are cleared ([invalidateCachedTokens]).
     */
    fun rebindToAccount(authEvidenceId: UUID, accountId: Long) {
        val evidence = authEvidenceRepository.findByIdOrNull(authEvidenceId)
            ?: error("EvidenceTrail not found: $authEvidenceId")
        evidence.subject = Subject.Account(accountId)
        authEvidenceRepository.save(evidence)
        authContextRepository.findByAuthEvidenceId(authEvidenceId).forEach { authContext ->
            authContext.accountId = accountId
            authContextRepository.save(authContext)
        }
        invalidateCachedTokens(authEvidenceId)
    }

    /** The proof of one completed orchestrator tool, merged via [EvidenceTrail.addAmr]. */
    fun applyEvidence(authEvidenceId: UUID, updates: List<MethodEvidence>) {
        val evidence = authEvidenceRepository.findByIdOrNull(authEvidenceId)
            ?: error("EvidenceTrail not found: $authEvidenceId")
        evidence.addAmr(updates, clock.instant())
        authEvidenceRepository.save(evidence)
        invalidateCachedTokens(authEvidenceId)
    }

    /**
     * Syncs [source]'s complete currently valid set (docs/05-api.md Abschnitt 3, see
     * [EvidenceTrail.replaceForSource]). [source] scopes which records may be removed, even when
     * [updates] is empty because everything expired.
     */
    fun applyEvidenceUpdate(authEvidenceId: UUID, updates: List<MethodEvidence>, source: String) {
        val evidence = authEvidenceRepository.findByIdOrNull(authEvidenceId)
            ?: error("EvidenceTrail not found: $authEvidenceId")
        evidence.replaceForSource(source, updates, clock.instant())
        authEvidenceRepository.save(evidence)
        invalidateCachedTokens(authEvidenceId)
    }

    /**
     * Evidence changed, so tokens minted from it are stale; both token providers cache by time
     * only. Clearing, not re-minting, keeps this service free of [TokenProvider]. The RefreshToken
     * goes too: [KcTokenProvider]'s refresh path (ADR-9) would keep renewing with pre-step-up
     * acr/amr. The session and its window stay: the next token continues it (ADR-43).
     */
    private fun invalidateCachedTokens(authEvidenceId: UUID) {
        authContextRepository.findByAuthEvidenceId(authEvidenceId).forEach { authContext ->
            authContext.accessToken = null
            authContext.accessExpiresAt = null
            authContext.refreshToken = null
            authContextRepository.save(authContext)
        }
    }

    /**
     * Links a [ChannelSession] to its evidence trail, creating one if needed, and syncs [source]'s
     * set via [applyEvidenceUpdate].
     */
    fun attachToChannel(channel: ChannelSession, source: String, updates: List<MethodEvidence>) {
        val accountId = checkNotNull(channel.accountId) { "Evidence update without a known account" }
        if (channel.authEvidenceId == null) {
            channel.authEvidenceId = createForAccount(accountId).authEvidenceId
            sessionManagementService.updateChannelSession(channel)
        }
        applyEvidenceUpdate(checkNotNull(channel.authEvidenceId), updates, source)
    }
}
