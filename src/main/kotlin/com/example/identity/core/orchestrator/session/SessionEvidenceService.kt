package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
@Transactional
class SessionEvidenceService(
    private val sessionEvidenceRepository: SessionEvidenceRecordRepository,
    private val appTokenSessionRepository: AppTokenSessionRepository,
    private val sessionManagementService: SessionManagementService,
    private val clock: Clock
) {

    fun createForAccount(accountId: AccountId): SessionEvidenceRecord =
        sessionEvidenceRepository.save(SessionEvidenceRecord(Subject.Account(accountId), clock.instant()))

    /** The evidence of a channel signed in with a one-time password: it belongs to the invitation. */
    fun createForInvitation(invitation: InvitationId): SessionEvidenceRecord =
        sessionEvidenceRepository.save(SessionEvidenceRecord(Subject.Invitation(invitation), clock.instant()))

    fun getSessionEvidence(sessionEvidenceId: SessionEvidenceId): SessionEvidenceRecord? =
        sessionEvidenceRepository.findBySessionEvidenceId(sessionEvidenceId)

    /**
     * Re-points an session evidence and its token contexts at another account (ADR-20), when an
     * assignment resolves a different account than the disposable one in hand. The trail is not
     * reset: what this session proved still counts. The cached tokens were minted for the yielding
     * account and are cleared ([invalidateCachedTokens]).
     */
    fun rebindToAccount(sessionEvidenceId: SessionEvidenceId, accountId: AccountId) {
        val evidence = sessionEvidenceRepository.findBySessionEvidenceId(sessionEvidenceId)
            ?: error("SessionEvidenceRecord not found: $sessionEvidenceId")
        evidence.subject = Subject.Account(accountId)
        sessionEvidenceRepository.save(evidence)
        appTokenSessionRepository.findBySessionEvidenceId(sessionEvidenceId).forEach { appTokenSession ->
            appTokenSession.accountId = accountId
            appTokenSessionRepository.save(appTokenSession)
        }
        invalidateCachedTokens(sessionEvidenceId)
    }

    /** The proof of one completed orchestrator tool, merged via [SessionEvidenceRecord.addAmr]. */
    fun applyEvidence(sessionEvidenceId: SessionEvidenceId, updates: List<MethodEvidence>) {
        val evidence = sessionEvidenceRepository.findBySessionEvidenceId(sessionEvidenceId)
            ?: error("SessionEvidenceRecord not found: $sessionEvidenceId")
        evidence.addAmr(updates, clock.instant())
        sessionEvidenceRepository.save(evidence)
        invalidateCachedTokens(sessionEvidenceId)
    }

    /**
     * Syncs [source]'s complete currently valid set (docs/05-api.md Abschnitt 3, see
     * [SessionEvidenceRecord.replaceForSource]). [source] scopes which records may be removed, even when
     * [updates] is empty because everything expired.
     */
    fun applyEvidenceUpdate(sessionEvidenceId: SessionEvidenceId, updates: List<MethodEvidence>, source: String) {
        val evidence = sessionEvidenceRepository.findBySessionEvidenceId(sessionEvidenceId)
            ?: error("SessionEvidenceRecord not found: $sessionEvidenceId")
        evidence.replaceForSource(source, updates, clock.instant())
        sessionEvidenceRepository.save(evidence)
        invalidateCachedTokens(sessionEvidenceId)
    }

    /**
     * Evidence changed, so tokens minted from it are stale; both token providers cache by time
     * only. Clearing, not re-minting, keeps this service free of [TokenProvider]. The RefreshToken
     * goes too: [KcTokenProvider]'s refresh path (ADR-9) would keep renewing with pre-step-up
     * acr/amr. The session and its window stay: the next token continues it (ADR-43).
     */
    private fun invalidateCachedTokens(sessionEvidenceId: SessionEvidenceId) {
        appTokenSessionRepository.findBySessionEvidenceId(sessionEvidenceId).forEach { appTokenSession ->
            appTokenSession.accessToken = null
            appTokenSession.accessExpiresAt = null
            appTokenSession.refreshToken = null
            appTokenSessionRepository.save(appTokenSession)
        }
    }

    /**
     * Links a [ChannelSession] to its session evidence, creating one if needed, and syncs [source]'s
     * set via [applyEvidenceUpdate].
     */
    fun attachToChannel(channel: ChannelSession, source: String, updates: List<MethodEvidence>) {
        val accountId = checkNotNull(channel.accountId) { "Evidence update without a known account" }
        if (channel.sessionEvidenceId == null) {
            channel.sessionEvidenceId = createForAccount(accountId).sessionEvidenceId
            sessionManagementService.updateChannelSession(channel)
        }
        applyEvidenceUpdate(checkNotNull(channel.sessionEvidenceId), updates, source)
    }
}
