package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.account.AccountService
import com.example.identity.core.account.RetractionAnchor
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceRepository
import com.example.identity.contract.tool_api.credentials.EnrollmentCleanup
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Hard-deletes an account and everything it exclusively owns (docs/05-api.md #2, "Konto löschen").
 * The external register (`personenverzeichnis.person`) is only referenced and stays. The
 * account module's child tables cascade; the change log survives (ADR-39). What hangs off no such
 * key is named in [deleteAccount]. Credentials are cleaned up through [EnrollmentCleanup], so no
 * method module is named here.
 */
@Service
@Transactional
class AccountDeletionService(
    private val accountService: AccountService,
    cleanups: List<EnrollmentCleanup>,
    private val deviceAccountLinkRepository: DeviceAccountLinkRepository,
    private val channelSessionRepository: ChannelSessionRepository,
    private val authContextRepository: AuthContextRepository,
    private val authEvidenceRepository: EvidenceTrailRepository,
    private val journeyTraceRepository: JourneyTraceRepository,
    private val attemptThrottleRepository: AttemptThrottleRepository
) {
    private val cleanupsByType: Map<String, EnrollmentCleanup> = cleanups.associateBy { it.enrollmentType }

    /** The module's credential row goes - unless another account's method still points at it. */
    private fun deleteCredential(accountId: Long, ref: com.example.identity.contract.tool_api.EnrollmentRef) {
        if (accountService.isEnrollmentSharedWithOtherAccount(accountId, ref)) return
        cleanupsByType[ref.type]?.delete(ref)
    }

    fun deleteAccount(accountId: Long) {
        accountService.allEnrollmentRefs(accountId).forEach { ref -> deleteCredential(accountId, ref) }

        deviceAccountLinkRepository.deleteByAccountId(accountId)

        // Every channel session of this account is logged out, on every device (docs/05-api.md
        // #2). Both the id and the navigation property must be cleared for authContext and
        // authEvidence, or Hibernate still reaches the deleted row as a transient instance.
        val channelSessions = channelSessionRepository.findByAccountId(accountId)
        channelSessions.forEach { session ->
            session.state = ChannelState.LOGGED_OUT
            session.authContextId = null
            session.authContext = null
            session.authEvidenceId = null
            session.authEvidence = null
            channelSessionRepository.save(session)
        }
        authContextRepository.findByAccountId(accountId).forEach { authContextRepository.delete(it) }
        authEvidenceRepository.findByAccountId(accountId).forEach { authEvidenceRepository.delete(it) }

        accountService.deleteAccount(accountId)

        // Strictly last. Bulk statements over `orchestrator.journey_trace` overlap entries the
        // running journey has not flushed yet. That forces an auto-flush mid-request, which bumps
        // session versions and ends in a spurious 409 CONCURRENT_MODIFICATION. After the account
        // delete the context is flushed anyway.
        //
        // The journey trace holds identity-adjacent data and has no cascading foreign key. It is
        // erased by account and by channel session, since early entries carry no accountId
        // (JourneyTraceRepository.deleteByAccountIdOrChannelSessionIdIn).
        journeyTraceRepository.deleteByAccountIdOrChannelSessionIdIn(
            accountId,
            channelSessions.mapNotNull { it.channelSessionId }.ifEmpty { listOf(NO_CHANNEL_SESSION) }
        )
        // Account-keyed counters only: clearing other scopes would make deletion a budget reset
        // (AttemptThrottleRepository.deleteBySubjectAndScopeIn). The modules' send budgets are
        // keyed by address, not account, and expire with the retention sweep (ADR-44).
        attemptThrottleRepository.deleteBySubjectAndScopeIn(
            accountId.toString(),
            listOf(ThrottleScope.ACCOUNT.name)
        )
    }

    /**
     * Revokes one authentication method instance through the same cleanup as [deleteAccount].
     * Device rebinding uses it too: the previous account's credential on that key must stop
     * matching (docs/09-dpop.md).
     */
    fun revokeMethod(accountId: Long, methodInstanceId: String) {
        accountService.enrollmentRefFor(accountId, methodInstanceId)?.let { ref -> deleteCredential(accountId, ref) }
        // Whatever only this credential backed stops being a valid claim (ADR-12). The rule lives
        // in retractClaimsOf.
        accountService.retractClaimsOf(
            accountId,
            methodInstanceId,
            RetractionAnchor.ACCOUNT_MANAGEMENT,
            reason = "method instance revoked"
        )
        accountService.deactivateAuthenticationMethod(accountId, methodInstanceId)
    }

    private companion object {
        /** Placeholder for "no channel session": a JPQL `in` clause rejects an empty collection. */
        private val NO_CHANNEL_SESSION: java.util.UUID = java.util.UUID(0L, 0L)
    }
}
