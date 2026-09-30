package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.session.id
import com.example.identity.contract.texts.Text
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.journey.JourneyService
import com.example.identity.core.orchestrator.kc.PeerAuthAssertion
import com.example.identity.core.orchestrator.domain.policy.AuthEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.domain.AmrSource
import com.example.identity.core.orchestrator.session.LiveChannel
import com.example.identity.core.orchestrator.session.AuthContextService
import com.example.identity.core.orchestrator.session.AuthEvidenceService
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.session.toMethodEvidence
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.core.account.SignInLog
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.kc.PeerAuthValidationException
import com.example.identity.contract.tool_api.claims.AcrLevel
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Service behind the kc facade's own endpoint (docs/05-api.md Abschnitt 3): an upsert on a
 * Keycloak-chosen [UUID] that resumes or creates the channel, then delegates to
 * [ChannelService.resumeChannel] like the App channel, so "which tool comes next" is decided once.
 */
@Service
@Transactional
class KcChannelService(
    private val sessionManagementService: SessionManagementService,
    private val kcChannelAccessGuard: KcChannelAccessGuard,
    private val channelService: ChannelService,
    private val journeyService: JourneyService,
    private val accountService: AccountService,
    private val authEvidenceService: AuthEvidenceService,
    private val restoreDataCodec: RestoreDataCodec,
    private val nativeAuthenticatorRegistry: NativeAuthenticatorRegistry,
    private val channelSessionRepository: ChannelSessionRepository,
    private val signInLog: SignInLog,
    private val authContextService: AuthContextService,
) {

    /**
     * Keycloak ended session [kcSessionId] of [subject]; the logout is Keycloak's
     * (docs/07-betrieb.md Abschnitt 3). Every live channel of that session ends with it (ADR-43):
     * the KEYCLOAK channels of its flow runs and, for an account, the APP channel whose login holds
     * it. Ending writes the sign-out. Without a live channel a Web sign-out is written directly. An
     * unknown account is nothing to log; an invitation always is (ADR-48).
     */
    fun signedOutAtKeycloak(subject: Subject, kcSessionId: String) {
        val appLogins = when (subject) {
            is Subject.Account -> {
                if (accountService.findAccount(subject.id) == null) return
                authContextService.findByKeycloakSessionId(kcSessionId).mapNotNull { it.authContextId }.toSet()
            }
            // A process access exists only in the Web channel.
            is Subject.Invitation -> emptySet()
        }
        val channels = when (subject) {
            is Subject.Account -> channelSessionRepository.findByAccountId(subject.id)
            is Subject.Invitation -> channelSessionRepository.findByInvitation(subject.hash)
        }
        val live = channels
            .filter {
                when (it.channel) {
                    ChannelType.KEYCLOAK -> it.durableKcSessionId == kcSessionId
                    ChannelType.APP -> it.authContextId in appLogins
                    null -> false
                }
            }
            .mapNotNull { LiveChannel.of(it) }
        if (live.isEmpty()) {
            // An App login's session ends only after its channel did, and that end was written.
            if (appLogins.isEmpty()) {
                when (subject) {
                    is Subject.Account -> signInLog.signedOut(subject.id, ChannelType.KEYCLOAK.name, endedBy = "HOLDER")
                    is Subject.Invitation -> signInLog.invitationSignedOut(subject.hash, ChannelType.KEYCLOAK.name, endedBy = "HOLDER")
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
        channelSessionId: UUID,
        assertion: PeerAuthAssertion,
        subject: Subject?,
        targetAcr: String?,
        amr: List<AmrEntry>? = null,
        restoreDataToken: String? = null,
        restoreDataKcSessionId: String? = null,
        availableTools: List<String>? = null,
        intent: String? = null
    ): ChannelResponse {
        // restoreDataToken carries an earlier flow run's state (docs/05-api.md Abschnitt 3). decode()
        // checks it is bound to [restoreDataKcSessionId], Keycloak's durable UserSessionModel id,
        // which differs from assertion.channelAnchor. A wrong, tampered or expired token yields null,
        // like "nothing to restore". Restored methods keep their original `source`. It never comes
        // with a non-empty [amr], so restored and live factors are applied separately below.
        val restoreData = restoreDataToken?.let { restoreDataCodec.decode(it, restoreDataKcSessionId) }
        // Keycloak's user and the restore token must name the same subject. Preferring one would let
        // a mis-attributed Keycloak user carry this session's evidence elsewhere. An invitation's
        // evidence is never restored (ADR-48), so a token naming an account never belongs to one.
        val restoredSubject = restoreData?.accountId?.let(Subject::Account)
        if (subject != null && restoredSubject != null && subject != restoredSubject) {
            throw OrchestratorException.invalidState(
                Text("Die Sitzung gehört zu einem anderen Konto"),
                "subject=${subject::class.simpleName} restoreData.accountId=${restoreData.accountId}"
            )
        }
        val effectiveSubject = subject ?: restoredSubject
        val effectiveAccountId = (effectiveSubject as? Subject.Account)?.id
        val restoredFactors = restoreData?.evidence?.factors.orEmpty()
        // method/maxAcr/factorTypes are fixed per authenticator type ([NativeAuthenticatorDescriptor]).
        // An unknown nativeToolId fails fast instead of pricing the proof as nothing.
        val liveFactors = amr.orEmpty().map { entry ->
            val descriptor = nativeAuthenticatorRegistry.descriptorFor(entry.nativeToolId)
                ?: throw OrchestratorException.notFound(Text("Unknown tool"), "nativeToolId=${entry.nativeToolId}")
            MethodEvidence(
                MethodName(descriptor.method),
                AcrLevel.of(descriptor.maxAcr),
                // Uncapped (docs/05-api.md Abschnitt 3). The enrolledUnderAcr cap stops an
                // orchestrator combination from escalating past the enrollment history. Keycloak's
                // native report is trusted as a whole already, so a cap would only break the rule
                // that two distinct factor types earn one tier above either alone.
                AcrLevels.HIGHEST,
                descriptor.factorTypes,
                source = AmrSource.KEYCLOAK,
                // Prefixed with nativeToolId, so the journey trace names the native authenticator.
                // Stable across re-reports of the same proof, so refresh detection still works.
                amrSourceId = "${entry.nativeToolId}:${entry.amrSourceId}",
            )
        }
        // Fails fast: an unknown accountId would otherwise surface later as an unrelated error.
        if (effectiveAccountId != null && accountService.findAccount(effectiveAccountId) == null) {
            throw OrchestratorException.notFound(Text("Account not found"), "accountId=${effectiveAccountId}")
        }

        val isFreshChannel = sessionManagementService.findChannelSessionById(channelSessionId) == null
        if (isFreshChannel) {
            // A new channel is bound to the channel id the extension signed for
            // (OrchestratorClient.upsertChannel), not to any validly signed value.
            if (assertion.channelAnchor != channelSessionId.toString()) {
                throw PeerAuthValidationException("Peer-auth channel_anchor does not name this channel")
            }
            // A process access is not raised (ADR-48): its session asks for a level only a new
            // flow run of its own could give, and an invitation binds only through its own proof.
            if (effectiveSubject is Subject.Invitation) throw invitationNotRaised()
            sessionManagementService.createKcChannelSession(
                channelSessionId,
                assertion.channelAnchor,
                effectiveAccountId,
                CHANNEL_TTL,
                // The Web channel's declaration of what it can render, taken verbatim like the App
                // channel's availableTools, never widened to the whole catalog.
                availableTools.orEmpty().toSet(),
                entryIntentFor(intent)
            )
        } else {
            // A guessed channelSessionId is not enough: the assertion must carry this channel's
            // anchor (docs/02-domaenenmodell.md Abschnitt 1).
            val channel = kcChannelAccessGuard.requireChannel(channelSessionId, assertion)
            // Step-up (docs/05-api.md Abschnitt 3): binds the channel to the account Keycloak knows,
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

        targetAcr?.let { sessionManagementService.raiseChannelAcrFloor(channelSessionId, it) }

        // Restored factors come only with a channel this call created. They are applied as the
        // entry journey's first transition (docs/04-orchestrierung.md #5, "RestoreData als erster
        // Übergang"), so its first decision already sees them.
        var response = if (isFreshChannel && restoredFactors.isNotEmpty()) {
            channelService.resumeChannel(
                sessionManagementService.reloadChannelSession(channelSessionId),
                Action.ApplyRestoredEvidence(AmrSource.KEYCLOAK, restoredFactors)
            )
        } else {
            channelService.resumeChannel(sessionManagementService.reloadChannelSession(channelSessionId))
        }

        // What a native Keycloak authenticator established in this run (docs/05-api.md Abschnitt 3,
        // ADR-8), merged into the same evidence as a tool proof. There is always a journey by now.
        if (liveFactors.isNotEmpty()) {
            val journey = journeyService.findActive(channelSessionId)
            val channel = LiveChannel.of(sessionManagementService.reloadChannelSession(channelSessionId))
            if (journey != null && channel != null) {
                journeyService.applyEvidenceUpdate(journey, channel, AmrSource.KEYCLOAK, liveFactors)
                response = channelService.resumeChannel(sessionManagementService.reloadChannelSession(channelSessionId))
            }
        }

        return response
    }

    private fun invitationNotRaised() = OrchestratorException.invalidState(
        Text("Dieses Einmalkennwort genuegt der verlangten Sicherheitsstufe nicht"),
        "invitation session asked for a flow run of its own"
    )

    /**
     * Called once by the authenticator's end-of-flow hook (docs/05-api.md Abschnitt 3), see
     * [RestoreData]. The token is bound to [kcSessionId], the fresh UserSessionModel id only the
     * caller knows ([RestoreDataCodec]). `null` for a channel with nothing worth restoring.
     */
    fun restoreData(channelSessionId: UUID, assertion: PeerAuthAssertion, kcSessionId: String, sessionExpiresAt: Instant? = null): String? {
        val channel = kcChannelAccessGuard.requireChannel(channelSessionId, assertion)
        // Every completed kc flow run makes this call, so it records the durable session id
        // without a separate write path. [sessionExpiresAt] is the latest end of that session
        // without further activity: the channel does not outlive it (ADR-43).
        val cappedExpiry = listOfNotNull(channel.expiresAt, sessionExpiresAt).minOrNull()
        if (channel.durableKcSessionId != kcSessionId || channel.expiresAt != cappedExpiry) {
            channel.durableKcSessionId = kcSessionId
            channel.expiresAt = cappedExpiry
            sessionManagementService.updateChannelSession(channel)
        }
        // The evidence of a process access belongs to its invitation. Carried into a later flow run,
        // it would count for whatever account that run signs in (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
        if (channel.invitation != null) return null
        val storedEvidence = channel.authEvidenceId?.let { authEvidenceService.getAuthEvidence(it) }
        val factors = storedEvidence?.amrEvidence?.map { it.toMethodEvidence() }
        if (channel.accountId == null && factors.isNullOrEmpty()) return null
        val coreEvidence = factors?.takeIf { it.isNotEmpty() }?.let { AuthEvidence(it) }
        return restoreDataCodec.encode(RestoreData(accountId = channel.accountId, evidence = coreEvidence), kcSessionId)
    }

    /**
     * The kc facade's reading of `intent`: `null` means [AuthIntent.KC_SELECT_METHOD], and only that
     * or [AuthIntent.REGISTER] is accepted. FAST_ACCESS/LOOKUP_LOGIN assume an App channel with
     * device binding (docs/04-orchestrierung.md #2/#3).
     */
    private fun entryIntentFor(intent: String?): AuthIntent {
        if (intent == null) return AuthIntent.KC_SELECT_METHOD
        val resolved = AuthIntent.fromRequest(intent)
        if (resolved != AuthIntent.KC_SELECT_METHOD && resolved != AuthIntent.REGISTER) {
            throw OrchestratorException.invalidState(Text("Dieser Vorgang ist im Web-Kanal nicht zugelassen"), "intent=${intent}")
        }
        return resolved
    }

    companion object {
        // One Keycloak flow run; at its end [restoreData] caps it at the session's end (ADR-43).
        private val CHANNEL_TTL: Duration = Duration.ofMinutes(30)
    }
}
