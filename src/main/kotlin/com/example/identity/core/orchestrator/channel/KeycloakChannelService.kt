package com.example.identity.core.orchestrator.channel

import com.example.identity.core.orchestrator.journey.JourneyEndedException
import com.example.identity.core.orchestrator.session.ChannelSessionEndedException
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.session.id
import com.example.identity.contract.texts.Text
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.journey.JourneyService
import com.example.identity.core.orchestrator.keycloak.PeerAuthAssertion
import com.example.identity.core.orchestrator.session.LiveChannel
import com.example.identity.core.orchestrator.session.AppTokenSessionService
import com.example.identity.core.orchestrator.session.SessionEvidenceService
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.session.toMethodEvidence
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.core.account.SignInLog
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidationException
import com.example.identity.core.orchestrator.session.KeycloakSessionEvidenceInitializer
import com.example.identity.core.orchestrator.session.KeycloakSessionEvidenceRepository
import com.example.identity.core.orchestrator.session.MethodEvidenceRow
import org.springframework.dao.DataIntegrityViolationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service behind the Keycloak facade's own endpoint (docs/05-api.md Abschnitt 3b): an upsert on a
 * Keycloak-chosen [UUID] that resumes or creates the channel, then delegates to
 * [ChannelService.resumeChannel] like the App channel, so "which tool comes next" is decided once.
 */
@Service
// ADR-43, I-2, see JourneyService
@Transactional(noRollbackFor = [ChannelSessionEndedException::class, JourneyEndedException::class])
class KeycloakChannelService(
    private val sessionManagementService: SessionManagementService,
    private val keycloakChannelAccessGuard: KeycloakChannelAccessGuard,
    private val channelService: ChannelService,
    private val journeyService: JourneyService,
    private val accountService: AccountService,
    private val sessionEvidenceService: SessionEvidenceService,
    private val keycloakSessionEvidenceRepository: KeycloakSessionEvidenceRepository,
    private val keycloakSessionEvidenceInitializer: KeycloakSessionEvidenceInitializer,
    private val clock: Clock,
    private val channelSessionRepository: ChannelSessionRepository,
    private val signInLog: SignInLog,
    private val appTokenSessionService: AppTokenSessionService,
) {

    /**
     * Keycloak ended session [kcSessionId] of [subject]; the logout is Keycloak's
     * (docs/07-betrieb.md Abschnitt 3). Every live channel of that session ends with it (ADR-43):
     * the WEB channels of its flow runs and, for an account, the APP channel whose login holds
     * it. Ending writes the sign-out. Without a live channel a Web sign-out is written directly. An
     * unknown account is nothing to log; an invitation always is (ADR-48).
     */
    fun signedOutAtKeycloak(subject: Subject, kcSessionId: String) {
        // The session is gone, and so is what it proved: a later sign-in starts from nothing.
        keycloakSessionEvidenceRepository.deleteBySession(kcSessionId)
        val appLogins = when (subject) {
            is Subject.Account -> {
                if (accountService.findAccount(subject.id) == null) return
                appTokenSessionService.findByKeycloakSessionId(kcSessionId).mapNotNull { it.appTokenSessionId }.toSet()
            }
            // A process access exists only in the Web channel.
            is Subject.Invitation -> emptySet()
        }
        val channels = when (subject) {
            is Subject.Account -> channelSessionRepository.findByAccountId(subject.id)
            is Subject.Invitation -> channelSessionRepository.findByInvitation(subject.id)
        }
        val live = channels
            .filter {
                when (it.channel) {
                    ChannelType.WEB -> it.durableKeycloakSessionId == kcSessionId
                    ChannelType.APP -> it.appTokenSessionId in appLogins
                    null -> false
                }
            }
            .mapNotNull { LiveChannel.of(it) }
        if (live.isEmpty()) {
            // An App login's session ends only after its channel did, and that end was written.
            if (appLogins.isEmpty()) {
                when (subject) {
                    is Subject.Account -> signInLog.signedOut(subject.id, ChannelType.WEB.name, endedBy = "HOLDER")
                    is Subject.Invitation -> signInLog.invitationSignedOut(subject.id, ChannelType.WEB.name, endedBy = "HOLDER")
                }
            }
        } else {
            live.forEach { channel ->
                journeyService.findActive(channel.session.id)?.let { journeyService.cancel(it, channel) }
                journeyService.endSession(channel, ChannelState.LOGGED_OUT)
            }
        }
    }

    fun upsertChannel(
        channelSessionId: ChannelSessionId,
        assertion: PeerAuthAssertion,
        subject: Subject?,
        targetAcr: String?,
        kcSessionId: String? = null,
        availableTools: List<String>? = null,
        intent: String? = null
    ): ChannelResponse {
        // Checked before anything changes, so a rejected level leaves the channel as it was.
        val targetFloor = targetAcr?.let(::requestedAcr)
        // What earlier flow runs of the same Keycloak session proved (ADR-59), each with its
        // original age. The rows of a session all belong to the account that signed in there.
        val restored = kcSessionId?.let { keycloakSessionEvidenceRepository.findLive(it, clock.instant()) }.orEmpty()
        // Keycloak's user and the session's evidence must name the same account. Preferring one
        // would let a mis-attributed Keycloak user carry this session's evidence elsewhere. An
        // invitation's evidence is never stored (ADR-48), so restored evidence never belongs to one.
        val restoredSubject = restored.firstOrNull()?.let { Subject.Account(AccountId(it.accountId)) }
        if (subject != null && restoredSubject != null && subject != restoredSubject) {
            throw OrchestratorException.invalidState(
                Text("Die Sitzung gehört zu einem anderen Konto"),
                "subject=${subject::class.simpleName} restored.accountId=${restoredSubject.id}"
            )
        }
        val effectiveSubject = subject ?: restoredSubject
        val effectiveAccountId = (effectiveSubject as? Subject.Account)?.id
        val restoredFactors = restored.map { it.toMethodEvidence() }
        // Fails fast: an unknown accountId would otherwise surface later as an unrelated error.
        if (effectiveAccountId != null && accountService.findAccount(effectiveAccountId) == null) {
            throw OrchestratorException.notFound(Text("Account not found"), "accountId=${effectiveAccountId}")
        }

        val isFreshChannel = sessionManagementService.findChannelSessionById(channelSessionId) == null
        if (isFreshChannel) {
            // A new channel is bound to the channel id the extension signed for
            // (OrchestratorClient.upsertChannel), not to any validly signed value.
            if (assertion.channelBinding != channelSessionId.toString()) {
                throw PeerAuthValidationException("Peer-auth channel_binding does not name this channel")
            }
            // A process access is not raised (ADR-48): its session asks for a level only a new
            // flow run of its own could give, and an invitation binds only through its own proof.
            if (effectiveSubject is Subject.Invitation) throw invitationNotRaised()
            sessionManagementService.createWebChannelSession(
                channelSessionId,
                assertion.channelBinding,
                effectiveAccountId,
                CHANNEL_TTL,
                // The Web channel's declaration of what it can render, taken verbatim like the App
                // channel's availableTools, never widened to the whole catalog.
                channelService.catalogToolsOf(availableTools.orEmpty()),
                entryIntentFor(intent)
            )
        } else {
            // A guessed channelSessionId is not enough: the assertion must carry this channel's
            // binding (docs/02-domaenenmodell.md Abschnitt 1).
            val channel = keycloakChannelAccessGuard.requireChannel(channelSessionId, assertion)
            // Step-up (docs/05-api.md Abschnitt 3b): binds the channel to the account Keycloak knows,
            // once. A request naming another subject - another account, or an account where an
            // invitation signed in, or the reverse - is a mismatch, not a rebind (I-5).
            val bound = channel.subject
            if (effectiveSubject is Subject.Invitation && bound != effectiveSubject) throw invitationNotRaised()
            if (effectiveSubject != null && bound != null && bound != effectiveSubject) {
                throw OrchestratorException.invalidState(
                    Text("Die Sitzung gehört zu einem anderen Konto"),
                    "channel=${bound::class.simpleName} requested=${effectiveSubject::class.simpleName}"
                )
            }
            // Only an account binds here; an invitation binds only through its own proof.
            if (effectiveAccountId != null && bound == null) {
                channel.subject = Subject.Account(effectiveAccountId)
                sessionManagementService.updateChannelSession(channel)
            }
        }

        targetFloor?.let { sessionManagementService.raiseChannelAcrFloor(channelSessionId, it.value) }

        // On a channel this call created, the entry journey's first decision already sees the
        // restored methods (docs/04-orchestrierung.md #8, "Übernommene Nachweise als erster Übergang").
        // Deciding without them would offer a way out (a step-up's RE_IDENTIFY) the proofs already
        // in hand make needless.
        val seedFactors = if (isFreshChannel) restoredFactors else emptyList()
        return if (seedFactors.isNotEmpty()) {
            channelService.resumeChannel(
                sessionManagementService.reloadChannelSession(channelSessionId),
                Action.ApplyRestoredEvidence(seedFactors)
            )
        } else {
            channelService.resumeChannel(sessionManagementService.reloadChannelSession(channelSessionId))
        }
    }

    private fun invitationNotRaised() = OrchestratorException.invalidState(
        Text("Dieses Einmalkennwort genuegt dem verlangten Sicherheitsniveau nicht"),
        "invitation session asked for a flow run of its own"
    )

    /**
     * Called once by the authenticator's end-of-flow hook (docs/05-api.md Abschnitt 3b) with Keycloak's
     * durable session id [kcSessionId]. Records what this channel proved for that session (ADR-59),
     * one row per method, and caps the channel at the session's end [sessionExpiresAt] (ADR-43).
     */
    fun flowEnded(channelSessionId: ChannelSessionId, assertion: PeerAuthAssertion, kcSessionId: String, sessionExpiresAt: Instant? = null) {
        val channel = keycloakChannelAccessGuard.requireChannel(channelSessionId, assertion)
        // Every completed Keycloak flow run makes this call, so it records the durable session id
        // without a separate write path. [sessionExpiresAt] is the latest end of that session
        // without further activity: the channel does not outlive it (ADR-43).
        val cappedExpiry = listOfNotNull(channel.expiresAt, sessionExpiresAt).minOrNull()
        if (channel.durableKeycloakSessionId != kcSessionId || channel.expiresAt != cappedExpiry) {
            channel.durableKeycloakSessionId = kcSessionId
            channel.expiresAt = cappedExpiry
            sessionManagementService.updateChannelSession(channel)
        }
        // The evidence of a process access belongs to its invitation. Carried into a later flow run,
        // it would count for whatever account that run signs in (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
        if (channel.invitation != null) return
        val accountId = channel.accountId ?: return
        val methods = channel.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) }?.methods.orEmpty()
        if (methods.isEmpty()) return
        val now = clock.instant()
        val expiresAt = sessionExpiresAt ?: now.plus(SESSION_EVIDENCE_FALLBACK_TTL)
        methods.map { MethodEvidenceRow.of(it.toMethodEvidence(), now) }.forEach { row ->
            // Created in its own transaction; a tab that ends at the same moment may have won.
            try {
                keycloakSessionEvidenceInitializer.createIfAbsent(kcSessionId, accountId.value, row, expiresAt)
            } catch (_: DataIntegrityViolationException) {
                // The row exists now; the update below decides.
            }
            keycloakSessionEvidenceRepository.updateIfYounger(
                kcSessionId, row.method, accountId.value, row.loa, row.enrolledUnderAcr,
                row.factorTypes, row.amrSourceId, row.axis, row.provenAt,
            )
        }
        keycloakSessionEvidenceRepository.extend(kcSessionId, expiresAt)
    }

    /** The Keycloak facade's reading of `intent`: [AuthIntent.fromRequest] for a [ChannelType.WEB] channel. */
    private fun entryIntentFor(intent: String?): AuthIntent =
        AuthIntent.fromRequest(intent, ChannelType.WEB)
            ?: throw OrchestratorException.invalidState(Text("Dieser Vorgang ist im Web-Kanal nicht zugelassen"), "intent=${intent}")

    companion object {
        // One Keycloak flow run; at its end [flowEnded] caps it at the session's end (ADR-43).
        private val CHANNEL_TTL: Duration = Duration.ofMinutes(30)

        // Only when Keycloak names no session end: as long as Keycloak's longest SSO session.
        private val SESSION_EVIDENCE_FALLBACK_TTL: Duration = Duration.ofHours(12)
    }
}
