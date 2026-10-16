package com.example.identity.core.orchestrator.channel

import com.example.identity.core.orchestrator.domain.journey.Answer
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.journey.JourneyEndedException
import com.example.identity.core.orchestrator.journey.Step
import com.example.identity.contract.tool_api.ToolVersion
import com.example.identity.contract.tool_api.InvalidInputException
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.session.id
import com.example.identity.core.orchestrator.session.SessionExpiredException
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.CallerProof
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.Journeys
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.JourneyStart
import com.example.identity.core.orchestrator.domain.journey.StartRefusal
import com.example.identity.core.orchestrator.domain.journey.StartingAccount
import com.example.identity.core.orchestrator.domain.journey.appChannelOpening
import com.example.identity.core.orchestrator.domain.journey.startRefusal
import com.example.identity.core.orchestrator.domain.journey.state.upTo
import com.example.identity.core.orchestrator.domain.journeys.ConfirmPeerLoginJourney
import com.example.identity.core.orchestrator.domain.journeys.DeleteAccountJourney
import com.example.identity.core.orchestrator.domain.journeys.ManageAuthMethodsJourney
import com.example.identity.core.orchestrator.domain.journeys.StepUpJourney
import com.example.identity.core.orchestrator.journey.JourneyService
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceService
import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.core.orchestrator.session.AppTokenSessionService
import com.example.identity.core.orchestrator.session.SessionEvidenceService
import com.example.identity.core.orchestrator.session.ChannelCreationRateLimitService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.session.LiveChannel
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.session.AppTokenIssuer
import com.example.identity.core.orchestrator.session.ChannelSessionEndedException
import com.example.identity.core.orchestrator.session.SessionRefusedException
import com.example.identity.core.orchestrator.session.TokenService
import com.example.identity.core.orchestrator.session.toCoreEvidence
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.claims.AcrLevel
import java.time.Duration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.example.identity.core.orchestrator.session.forLog

/**
 * The channel-level entry points. Which tool comes next belongs to the journey ([JourneyService]);
 * this class decides which journey a request means and returns the response envelope.
 */
@Service
@Transactional(noRollbackFor = [ChannelSessionEndedException::class, JourneyEndedException::class]) // ADR-43, I-2, see JourneyService
class ChannelService(
    private val sessionManagementService: SessionManagementService,
    private val accountService: AccountService,
    private val appTokenSessionService: AppTokenSessionService,
    private val sessionEvidenceService: SessionEvidenceService,
    private val authPolicy: AuthPolicy,
    private val channelAccessGuard: ChannelAccessGuard,
    private val journeyService: JourneyService,
    private val tokenService: TokenService,
    private val appTokenIssuer: AppTokenIssuer,
    private val channelCreationRateLimitService: ChannelCreationRateLimitService,
    private val journeyTraceService: JourneyTraceService,
    private val toolRegistry: ToolHandlerRegistry,
    private val responseAssembler: ChannelResponseAssembler,
) {

    /**
     * Always creates a new ChannelSession: DPoP proves the device but is no lookup key for a
     * session (docs/02-domaenenmodell.md #3). The client resumes via [getChannel]. [intent] is
     * remembered on the channel, so resume and cancel restart it. Only journeys starting from the
     * device link consult [DeviceAccountLink]; REGISTER and LOGIN_LOOKUP mean another account.
     */
    fun initializeChannel(
        device: CallerProof.DeviceKey,
        requestedAcrFloor: String?,
        intent: String? = null,
        availableTools: List<String> = emptyList()
    ): ChannelResponse {
        // Before anything is created: this endpoint is unauthenticated, and every fresh channel
        // resets the attempt budget.
        channelCreationRateLimitService.recordAndAssertWithinBudget(device.thumbprint)

        val entryJourney = Journeys.fromRequest(intent, ChannelType.APP)
            ?: throw OrchestratorException.invalidState(Text("Dieser Vorgang kann keinen Kanal eroeffnen"), "intent=${intent}")

        // CONFIRM_PEER_LOGIN depends on the link like FAST_ACCESS: without one it aborts.
        val linkedAccountId = if (entryJourney.appChannelOpening?.startsWith == StartingAccount.LINKED_TO_DEVICE) {
            sessionManagementService.findLinkedAccountId(device.thumbprint)
        } else {
            null
        }
        val channel = sessionManagementService
            .createChannelSession(device.thumbprint, ChannelType.APP, CHANNEL_TTL, linkedAccountId)
        channel.entryJourney = entryJourney
        // Fixed for the channel's lifetime (docs/03-tool-architektur.md); the backend switch is read live.
        channel.availableClientTools = catalogToolsOf(availableTools)
        sessionManagementService.updateChannelSession(channel)
        requestedAcrFloor?.let { sessionManagementService.raiseChannelAcrFloor(channel.id, requestedAcr(it).value) }

        return resumeChannel(sessionManagementService.reloadChannelSession(channel.id))
    }

    /**
     * What a client declared it can render, each tool in the one version it speaks (`enroll-sms@1`,
     * ADR-51), cut to what the server serves. A tool in a version the server does not serve is
     * left out like an unknown one: it is never offered. A name without its version, or one tool in
     * two versions, is a client error.
     */
    fun catalogToolsOf(declared: Collection<String>): MutableSet<String> {
        val versions = declared.map(ToolVersion::parse)
        require(versions.distinctBy { it.toolId }.size == versions.size) { "A tool is declared in more than one version" }
        val served = toolRegistry.tools().flatMapTo(HashSet()) { tool -> tool.versions.map { ToolVersion(tool.toolId, it) } }
        return versions.filter { it in served }.mapTo(mutableSetOf()) { it.toString() }
    }

    /** The guaranteed resume entry point (docs/05-api.md #3a): re-derives the currently due `next`. */
    fun getChannel(channelSessionId: ChannelSessionId, caller: CallerProof): ChannelResponse =
        resumeChannel(channelAccessGuard.requireChannel(channelSessionId, caller))

    /**
     * Whether this device is linked to an account (docs/02-domaenenmodell.md #1). A pure read, so
     * the entry screen can offer "sign in with this device". No name: holding the device key proves
     * no factor, and a stolen device should not say whose it is.
     */
    fun findDeviceLink(device: CallerProof.DeviceKey): DeviceLinkResponse {
        val accountId = sessionManagementService.findLinkedAccountId(device.thumbprint) ?: return DeviceLinkResponse(linked = false)
        return DeviceLinkResponse(
            linked = true,
            accountId = accountId,
            boundCredentials = boundCredentials(accountId, device.thumbprint)
        )
    }

    /**
     * The active methods as their own resource (docs/05-api.md #3a). Empty, not an error, until a
     * factor was proven here ([ChannelSession.hasProvenFactor]); a recognized device is not enough.
     */
    fun getMethods(channelSessionId: ChannelSessionId, caller: CallerProof): MethodsResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, caller)
        val methods = if (channel.hasProvenFactor) {
            channel.accountId?.let { accountService.findAccount(it)?.activeAuthenticationMethods }
        } else {
            null
        }
        return MethodsResponse(responseAssembler.toActiveMethodViews(methods))
    }

    /**
     * The AccessToken of the channel's session ([AppTokenIssuer], ADR-43), cached, refreshed or
     * re-minted after a step-up. APP only: a WEB client holds its own Keycloak tokens.
     * [minValiditySeconds] is the caller's tolerance; the backend decides whether to mint anew.
     */
    // noRollbackFor: an expired login ends the channel and answers 410; the ending must commit.
    @Transactional(noRollbackFor = [OrchestratorException::class])
    fun getToken(channelSessionId: ChannelSessionId, caller: CallerProof, minValiditySeconds: Long): TokenResponse {
        val channel = requireAuthenticatedApp(channelAccessGuard.requireChannel(channelSessionId, caller))
        val pair = try {
            appTokenIssuer.tokenFor(channel.session, minValiditySeconds)
        } catch (e: SessionExpiredException) {
            throw loginEnded(channel, e)
        } catch (e: SessionRefusedException) {
            throw loginEnded(channel, e)
        }
        return TokenResponse(accessToken = pair.accessToken, accessExpiresAt = pair.accessExpiresAt, refreshExpiresAt = pair.refreshExpiresAt)
    }

    /** The session behind the channel is gone, so the channel ends with it (ADR-43). */
    private fun loginEnded(channel: LiveChannel, cause: RuntimeException): OrchestratorException {
        journeyService.endSession(channel, ChannelState.EXPIRED)
        return OrchestratorException.processGone(Text("Die Anmeldung ist abgelaufen. Bitte melden Sie sich neu an."), cause.message)
    }

    /** The business ID-token claims, a resource separate from the AccessToken's claims. */
    fun getIdClaims(channelSessionId: ChannelSessionId, caller: CallerProof): Map<String, Any?> {
        val channel = requireAuthenticatedApp(channelAccessGuard.requireChannel(channelSessionId, caller)).session
        return tokenService.idClaims(channel.appTokenSessionId!!)
    }

    /**
     * Token and ID claims exist only for an authenticated APP channel. The type is checked first,
     * so a WEB channel gets 409, not a 500 from the missing context.
     */
    private fun requireAuthenticatedApp(channel: ChannelSession): LiveChannel {
        if (channel.channel != ChannelType.APP) {
            throw OrchestratorException.invalidState(Text("Token retrieval is only supported for APP channels"))
        }
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState(Text("Channel must be AUTHENTICATED for token/claims access"))
        }
        checkNotNull(channel.appTokenSessionId) { "AUTHENTICATED channel without appTokenSessionId" }
        return LiveChannel.require(channel)
    }

    /**
     * Resumes the channel and its active journey. [KeycloakChannelService] reuses it for the Keycloak facade's
     * upsert (docs/05-api.md Abschnitt 3b). [restoredEvidence] matters only when a fresh journey
     * starts (see [JourneyService.start]).
     */
    internal fun resumeChannel(channel: ChannelSession, restoredEvidence: List<MethodEvidence>? = null): ChannelResponse {
        // An ended channel (docs/02-domaenenmodell.md #3) is only shown, never resumed, so an old
        // channelSessionId cannot open a fresh login on a dead channel.
        val live = LiveChannel.of(channel) ?: return responseAssembler.respond(channel)

        val channelId = channel.id
        journeyService.findActive(channelId)?.let {
            val step = journeyService.stepOf(it, channel)
            return responseAssembler.respond(channel, step.next, step.stepData)
        }
        if (channel.state == ChannelState.AUTHENTICATED) return responseAssembler.respond(channel)

        return startEntryJourney(live, restoredEvidence)
    }

    /** Starts [journey] on a live channel that may start it, and answers with its first step. */
    private fun startJourney(channelSessionId: ChannelSessionId, caller: CallerProof, start: JourneyStart<*>): ChannelResponse {
        val live = channelAccessGuard.requireLiveChannel(channelSessionId, caller)
        requireStartable(live, start.journey)
        return respondAfter(channelSessionId, journeyService.start(live, start))
    }

    /** The channel as the journey left it, with the journey's next [step]. */
    private fun respondAfter(channelSessionId: ChannelSessionId, step: Step): ChannelResponse =
        responseAssembler.respond(sessionManagementService.reloadChannelSession(channelSessionId), step.next, step.stepData)

    private fun startEntryJourney(channel: LiveChannel, restoredEvidence: List<MethodEvidence>? = null): ChannelResponse {
        val step = journeyService.startEntryJourney(channel, restoredEvidence)
        return respondAfter(channel.session.id, step)
    }

    fun raiseRequiredAcr(channelSessionId: ChannelSessionId, caller: CallerProof, requiredAcr: String): ChannelResponse {
        val live = channelAccessGuard.requireLiveChannel(channelSessionId, caller)
        sessionManagementService.raiseChannelAcrFloor(channelSessionId, requestedAcr(requiredAcr).value)
        val refreshed = live.session

        // Not logged in yet: nothing to step up from. The raised floor applies to the running
        // login or registration, which reads it on each transition. A STEP_UP here could claim
        // AUTHENTICATED on cancel without proof (docs/invarianten.md I-4).
        if (refreshed.state?.isLoggedIn != true) return resumeChannel(refreshed)
        requireStartable(live, StepUpJourney)

        val floor = refreshed.acrFloor?.let(AcrLevel::parse) ?: AcrLevels.DEFAULT_REQUIRED_ACR
        val account = refreshed.accountId?.let { accountService.findAccount(it) }
        if (authPolicy.isSatisfied(currentEvidence(refreshed), floor, account)) return responseAssembler.respond(refreshed)

        val startingAcr = authPolicy.resolveAcr(currentEvidence(refreshed), account)
        val step = journeyService.start(live, StepUpJourney.toward(startingAcr upTo floor))
        return respondAfter(channelSessionId, step)
    }

    /** Abandons the running journey and offers a fresh start where applicable. */
    fun cancelActiveJourney(channelSessionId: ChannelSessionId, caller: CallerProof): ChannelResponse {
        val channel = channelAccessGuard.requireLiveChannel(channelSessionId, caller)
        val active = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState(Text("No active journey to cancel for this channel"))

        journeyService.cancel(active, channel)

        return if (channel.session.state == ChannelState.AUTHENTICATED) responseAssembler.respond(channel.session) else startEntryJourney(channel)
    }

    /**
     * The logout, without a journey: cancels any running journey and ends the channel for good
     * (docs/02-domaenenmodell.md #3). Unlike [cancelActiveJourney], it is never resurrected.
     */
    fun logout(channelSessionId: ChannelSessionId, caller: CallerProof) {
        // An ended channel stays in its final state.
        val channel = LiveChannel.of(channelAccessGuard.requireChannel(channelSessionId, caller)) ?: return
        val activeJourney = journeyService.findActive(channelSessionId)
        if (activeJourney != null) {
            journeyService.cancel(activeJourney, channel)
        } else {
            journeyTraceService.recordForChannel(channel.session.forLog(), "LOGGED_OUT")
        }
        journeyService.endSession(channel, ChannelState.LOGGED_OUT)
    }

    /**
     * Voluntarily add another method. The loa2 gate and a preceding step-up belong to
     * `ManageAuthMethodsJourney`, so the wish survives that detour.
     */
    fun startManageMethods(channelSessionId: ChannelSessionId, caller: CallerProof): ChannelResponse =
        startManage(channelSessionId, caller, ManageAuthMethodsState.AddRequested)

    /**
     * Deactivate an active method instance, addressed by [methodInstanceId], since several
     * instances can share a method name. Not restricted to this device's instances: a lost device
     * must be removable from any session.
     */
    fun deactivateMethod(channelSessionId: ChannelSessionId, caller: CallerProof, methodInstanceId: String): ChannelResponse =
        startManage(channelSessionId, caller, ManageAuthMethodsState.RemoveRequested(methodInstanceId))

    /**
     * Change an active credential in place: its enrollment runs again and the new instance replaces
     * [methodInstanceId]. Only for a method whose enrollment is [com.example.identity.contract.tool_api.Tool.changeable];
     * anything else is refused here, not inside the journey.
     */
    fun changeMethod(channelSessionId: ChannelSessionId, caller: CallerProof, methodInstanceId: String): ChannelResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, caller)
        val target = channel.accountId?.let { accountService.findAccount(it) }?.activeAuthenticationMethods?.firstOrNull { it.id == methodInstanceId }
            ?: throw OrchestratorException.notFound(Text("Dieses Anmeldeverfahren ist nicht aktiv"))
        if (toolRegistry.changeToolOf(target.method) == null) {
            throw OrchestratorException.invalidState(
                Text("Das Verfahren '{method}' lässt sich nicht ändern; entfernen Sie es und richten Sie es neu ein", "method" to target.method)
            )
        }
        return startManage(channelSessionId, caller, ManageAuthMethodsState.ChangeRequested(methodInstanceId))
    }

    /**
     * Withdraws an account-owned attribute through the same journey and gate as removing a method,
     * since it can take credentials with it. Only local anchors the holder may give up
     * (`AnchorRule.retractableByHolder`) qualify; an identity anchor does not. An unknown wire name
     * is refused here with 409, not as a silent no-op inside the journey.
     */
    fun retractAttribute(channelSessionId: ChannelSessionId, caller: CallerProof, attribute: String): ChannelResponse {
        val attributeType = AttributeType.fromWireName(attribute)
            ?: throw OrchestratorException.invalidState(Text("Unbekanntes Attribut: {attribute}", "attribute" to attribute))
        if (!attributeType.isLocalAnchor) {
            throw OrchestratorException.invalidState(
                Text("'{attribute}' gehoert nicht dem Konto ({authority}) und kann hier nicht zurueckgenommen werden", "attribute" to attribute, "authority" to attributeType.authority)
            )
        }
        if (attributeType.anchorRule?.retractableByHolder != true) {
            throw OrchestratorException.invalidState(
                Text("'{attribute}' weist die Identität dieses Kontos nach und kann nicht selbst zurückgenommen werden", "attribute" to attribute)
            )
        }
        return startManage(channelSessionId, caller, ManageAuthMethodsState.RetractAttributeRequested(attributeType))
    }

    private fun startManage(channelSessionId: ChannelSessionId, caller: CallerProof, wish: ManageAuthMethodsState.Wish): ChannelResponse {
        return startJourney(channelSessionId, caller, ManageAuthMethodsJourney.toCarryOut(wish))
    }

    /**
     * Confirms a Web-channel QR login from this authenticated App channel (docs/04-orchestrierung.md,
     * CONFIRM_PEER_LOGIN). The cold entry is `POST /app/channels` with that journey as `intent`;
     * both reach [ConfirmPeerLoginState.Requested].
     */
    fun startPeerLogin(channelSessionId: ChannelSessionId, caller: CallerProof): ChannelResponse {
        return startJourney(channelSessionId, caller, ConfirmPeerLoginJourney.start())
    }

    /**
     * Starts account deletion: a yes/no confirmation, then a fresh re-proof of an active factor,
     * then the deletion (docs/05-api.md #3a, "Konto löschen").
     */
    fun startDeleteAccount(channelSessionId: ChannelSessionId, caller: CallerProof): ChannelResponse {
        return startJourney(channelSessionId, caller, DeleteAccountJourney.start())
    }

    /** Applies [startRefusal] before a journey starts on an already open [channel]. */
    private fun requireStartable(channel: LiveChannel, journey: Journey<*>) {
        when (journey.startRefusal(channel.session.state, hasAccount = channel.session.accountId != null)) {
            null -> return
            StartRefusal.NOT_LOGGED_IN ->
                throw OrchestratorException.invalidState(Text("Dieser Vorgang setzt eine Anmeldung voraus"), "intent=${journey.name}")
            StartRefusal.NO_ACCOUNT ->
                throw OrchestratorException.invalidState(Text("Nur mit einem Konto moeglich"), "intent=${journey.name}")
            StartRefusal.NOT_STARTABLE_WHEN_AUTHENTICATED -> error("${journey.name} is not started on an authenticated channel")
        }
    }

    /**
     * The user's answer to what the current step waits on instead of a tool run. Anything but
     * `accept`/`decline` is rejected here (400), so no journey has to.
     */
    fun answer(channelSessionId: ChannelSessionId, caller: CallerProof, answer: String): ChannelResponse {
        val parsed = requireNotNull(Answer.fromWire(answer)) { "Unknown answer" }
        val channel = channelAccessGuard.requireLiveChannel(channelSessionId, caller)
        val active = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState(Text("No active journey for this channel"))
        val step = journeyService.answer(active, channel, parsed)
        return respondAfter(channelSessionId, step)
    }

    private fun currentEvidence(channel: ChannelSession): SessionEvidence =
        channel.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) }?.toCoreEvidence() ?: SessionEvidence(emptyList())

    /**
     * What else this device is known by: for every key-bound credential of [accountId] on
     * [deviceKey], the reference its enrollment left to show (`AuthMethodView.reference`).
     * Generic: no method name appears here.
     */
    private fun boundCredentials(accountId: AccountId, deviceKey: String): List<BoundCredentialView> =
        accountService.findAccount(accountId)?.activeAuthenticationMethods.orEmpty().mapNotNull { instance ->
            val module = toolRegistry.moduleOf(instance.method) ?: return@mapNotNull null
            if (!module.livesOn(instance.boundKeyRef, deviceKey)) return@mapNotNull null
            instance.reference?.let { BoundCredentialView(method = instance.method, reference = it) }
        }

    companion object {
        // Only until AUTHENTICATED; from then on the channel lives as long as its session (ADR-43).
        // The device's long-lived identity lives in DeviceAccountLink.
        private val CHANNEL_TTL: Duration = Duration.ofHours(24)
    }
}

/**
 * A level a client asked for (App `requiredAcr`, Keycloak `targetAcr`). An unknown one is a 400,
 * never a silent `none`: a typo must not leave the floor where it was.
 */
internal fun requestedAcr(raw: String): AcrLevel = AcrLevel.parse(raw) ?: throw InvalidInputException(
    Text("Unbekanntes Sicherheitsniveau '{acr}' - bekannt sind {known}", "acr" to raw, "known" to AcrLevel.KNOWN.joinToString())
)
