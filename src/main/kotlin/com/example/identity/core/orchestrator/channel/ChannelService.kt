package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.InvalidInputException
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.session.id
import com.example.identity.core.orchestrator.session.SessionExpiredException
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
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
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.claims.authority
import com.example.identity.contract.tool_api.claims.anchorRule
import com.example.identity.contract.tool_api.claims.isLocalAnchor
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.claims.AcrLevel
import java.time.Duration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.example.identity.core.orchestrator.session.forLog

/**
 * The channel-level entry points. Which tool comes next belongs to the journey ([JourneyService]);
 * this class decides which intent a request means and returns the response envelope.
 */
@Service
@Transactional(noRollbackFor = [ChannelSessionEndedException::class]) // ADR-43, see JourneyService
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
     * remembered on the channel, so resume and cancel restart it. Only intents starting from the
     * device link consult [DeviceAccountLink]; REGISTER and LOGIN_LOOKUP mean another account.
     */
    fun initializeChannel(
        bindingKeyRef: String,
        requestedAcrFloor: String?,
        intent: String? = null,
        availableTools: List<String> = emptyList()
    ): ChannelResponse {
        // Before anything is created: this endpoint is unauthenticated, and every fresh channel
        // resets the attempt budget.
        channelCreationRateLimitService.recordAndAssertWithinBudget(bindingKeyRef)

        val entryIntent = AuthIntent.fromRequest(intent)
            ?: throw OrchestratorException.invalidState(Text("Unbekannter Vorgang"), "intent=${intent}")
        if (!entryIntent.isEntryIntent) {
            throw OrchestratorException.invalidState(Text("Dieser Vorgang kann keinen Kanal eroeffnen"), "entryIntent=${entryIntent}")
        }

        // CONFIRM_PEER_LOGIN depends on the link like FAST_ACCESS: without one its strategy aborts.
        val linkedAccountId = if (entryIntent.startsFromDeviceLink) {
            sessionManagementService.findLinkedAccountId(bindingKeyRef)
        } else {
            null
        }
        val channel = sessionManagementService
            .createChannelSession(bindingKeyRef, ChannelType.APP, CHANNEL_TTL, linkedAccountId)
        channel.entryIntent = entryIntent
        // Fixed for the channel's lifetime (docs/03-tool-architektur.md); the backend switch is read live.
        channel.availableClientTools = availableTools.toMutableSet()
        sessionManagementService.updateChannelSession(channel)
        requestedAcrFloor?.let { sessionManagementService.raiseChannelAcrFloor(channel.id, requestedAcr(it).value) }

        return resumeChannel(sessionManagementService.reloadChannelSession(channel.id))
    }

    /** The guaranteed resume entry point (docs/05-api.md #2): re-derives the currently due `next`. */
    fun getChannel(channelSessionId: ChannelSessionId, bindingKeyRef: String): ChannelResponse =
        resumeChannel(channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef))

    /**
     * Whether this device is linked to an account (docs/02-domaenenmodell.md #1). A pure read, so
     * the entry screen can offer "sign in with this device". No name: holding the device key proves
     * no factor, and a stolen device should not say whose it is.
     */
    fun findDeviceLink(bindingKeyRef: String): DeviceLinkResponse {
        val accountId = sessionManagementService.findLinkedAccountId(bindingKeyRef) ?: return DeviceLinkResponse(linked = false)
        return DeviceLinkResponse(
            linked = true,
            accountId = accountId,
            boundCredentials = boundCredentials(accountId, bindingKeyRef)
        )
    }

    /**
     * The active methods as their own resource (docs/05-api.md #2). Empty, not an error, until a
     * factor was proven here ([ChannelSession.hasProvenFactor]); a recognized device is not enough.
     */
    fun getMethods(channelSessionId: ChannelSessionId, bindingKeyRef: String): MethodsResponse {
        val channel = channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)
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
    fun getToken(channelSessionId: ChannelSessionId, bindingKeyRef: String, minValiditySeconds: Long): TokenResponse {
        val channel = requireAuthenticatedApp(channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef))
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
    fun getIdClaims(channelSessionId: ChannelSessionId, bindingKeyRef: String): Map<String, Any?> {
        val channel = requireAuthenticatedApp(channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)).session
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
     * Resumes the channel and its active journey. [KcChannelService] reuses it for the kc facade's
     * upsert (docs/05-api.md Abschnitt 3). [seedAction] matters only when a fresh journey starts
     * (see [JourneyService.start]).
     */
    internal fun resumeChannel(channel: ChannelSession, seedAction: Action? = null): ChannelResponse {
        // An ended channel (docs/02-domaenenmodell.md #3) is only shown, never resumed, so an old
        // channelSessionId cannot open a fresh login on a dead channel.
        val live = LiveChannel.of(channel) ?: return responseAssembler.respond(channel)

        val channelId = channel.id
        journeyService.findActive(channelId)?.let {
            val step = journeyService.stepOf(it, channel)
            return responseAssembler.respond(channel, step.next, step.stepData)
        }
        if (channel.state == ChannelState.AUTHENTICATED) return responseAssembler.respond(channel)

        return startEntryJourney(live, seedAction)
    }

    /** A level a client asked for (`requiredAcr`); an unknown one is a 400, not an internal error. */
    private fun requestedAcr(raw: String): AcrLevel = AcrLevel.parse(raw) ?: throw InvalidInputException(
        Text("Unbekanntes Sicherheitsniveau '{acr}' - bekannt sind {known}", "acr" to raw, "known" to AcrLevel.KNOWN.joinToString())
    )

    private fun startEntryJourney(channel: LiveChannel, seedAction: Action? = null): ChannelResponse {
        val step = journeyService.startEntryJourney(channel, seedAction)
        return responseAssembler.respond(sessionManagementService.reloadChannelSession(channel.session.id), step.next, step.stepData)
    }

    fun raiseRequiredAcr(channelSessionId: ChannelSessionId, bindingKeyRef: String, requiredAcr: String): ChannelResponse {
        val live = channelAccessGuard.requireLiveChannel(channelSessionId, bindingKeyRef)
        sessionManagementService.raiseChannelAcrFloor(channelSessionId, requestedAcr(requiredAcr).value)
        val refreshed = live.session

        // Not logged in yet: nothing to step up from. The raised floor applies to the running
        // login or registration, which reads it on each transition. A STEP_UP here could claim
        // AUTHENTICATED on cancel without proof (docs/invarianten.md I-4).
        if (refreshed.state?.isLoggedIn != true) return resumeChannel(refreshed)

        val floor = refreshed.acrFloor?.let(AcrLevel::parse) ?: AcrLevels.DEFAULT_REQUIRED_ACR
        val account = refreshed.accountId?.let { accountService.findAccount(it) }
        if (authPolicy.isSatisfied(currentEvidence(refreshed), floor, account)) return responseAssembler.respond(refreshed)

        val step = journeyService.startTowardAcr(
            live,
            targetAcr = floor,
            startingAcr = authPolicy.resolveAcr(currentEvidence(refreshed), account)
        )
        return responseAssembler.respond(sessionManagementService.reloadChannelSession(channelSessionId), step.next, step.stepData)
    }

    /** Abandons the running journey and offers a fresh start where applicable. */
    fun cancelActiveJourney(channelSessionId: ChannelSessionId, bindingKeyRef: String): ChannelResponse {
        val channel = channelAccessGuard.requireLiveChannel(channelSessionId, bindingKeyRef)
        val active = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState(Text("No active journey to cancel for this channel"))

        journeyService.cancel(active, channel)

        return if (channel.session.state == ChannelState.AUTHENTICATED) responseAssembler.respond(channel.session) else startEntryJourney(channel)
    }

    /**
     * Starts a LOGOUT journey with a confirmation prompt, on AUTHENTICATED channels only. The
     * logout happens when the user confirms via POST .../answer.
     */
    fun startLogout(channelSessionId: ChannelSessionId, bindingKeyRef: String): ChannelResponse {
        val channel = channelAccessGuard.requireLiveChannel(channelSessionId, bindingKeyRef)
        if (channel.session.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState(Text("Channel must be AUTHENTICATED to start a logout journey"))
        }
        val activeJourney = journeyService.findActive(channelSessionId)
        if (activeJourney != null) {
            journeyService.cancel(activeJourney, channel)
        }
        val step = journeyService.start(channel, AuthIntent.LOGOUT)
        return responseAssembler.respond(sessionManagementService.reloadChannelSession(channelSessionId), step.next, step.stepData)
    }

    /**
     * Hard logout without confirmation: cancels any running journey and ends the channel for good
     * (docs/02-domaenenmodell.md #3). Unlike [cancelActiveJourney], it is never resurrected.
     */
    fun logout(channelSessionId: ChannelSessionId, bindingKeyRef: String) {
        // An ended channel stays in its final state.
        val channel = LiveChannel.of(channelAccessGuard.requireChannel(channelSessionId, bindingKeyRef)) ?: return
        val activeJourney = journeyService.findActive(channelSessionId)
        if (activeJourney != null) {
            journeyService.cancel(activeJourney, channel)
        } else {
            journeyTraceService.recordForChannel(channel.session.forLog(), "LOGGED_OUT")
        }
        journeyService.endSession(channel, ChannelState.LOGGED_OUT)
    }

    /**
     * Voluntarily add another method. The loa2 gate and a preceding step-up belong to the MANAGE
     * strategy, so the wish survives that detour.
     */
    fun startManageMethods(channelSessionId: ChannelSessionId, bindingKeyRef: String): ChannelResponse =
        startManage(channelSessionId, bindingKeyRef, ManageAuthMethodsState.AddRequested)

    /**
     * Deactivate an active method instance, addressed by [methodInstanceId], since several
     * instances can share a method name. Not restricted to this device's instances: a lost device
     * must be removable from any session.
     */
    fun deactivateMethod(channelSessionId: ChannelSessionId, bindingKeyRef: String, methodInstanceId: String): ChannelResponse =
        startManage(channelSessionId, bindingKeyRef, ManageAuthMethodsState.RemoveRequested(methodInstanceId))

    /**
     * Withdraws an account-owned attribute through the same journey and gate as removing a method,
     * since it can take credentials with it. Only local anchors the holder may give up
     * (`AnchorRule.retractableByHolder`) qualify; an identity anchor does not. An unknown wire name
     * is refused here with 409, not as a silent no-op inside the journey.
     */
    fun retractAttribute(channelSessionId: ChannelSessionId, bindingKeyRef: String, attribute: String): ChannelResponse {
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
        return startManage(channelSessionId, bindingKeyRef, ManageAuthMethodsState.RetractAttributeRequested(attributeType))
    }

    private fun startManage(channelSessionId: ChannelSessionId, bindingKeyRef: String, wish: ManageAuthMethodsState): ChannelResponse {
        val live = channelAccessGuard.requireLiveChannel(channelSessionId, bindingKeyRef)
        val channel = live.session
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState(Text("Channel must be AUTHENTICATED to manage methods"))
        }
        // A process access (one-time password) is signed in without an account and serves only its process.
        if (channel.accountId == null) throw OrchestratorException.invalidState(Text("Nur mit einem Konto moeglich"))

        val step = journeyService.start(live, AuthIntent.MANAGE_AUTH_METHODS, seed = wish)
        return responseAssembler.respond(sessionManagementService.reloadChannelSession(channelSessionId), step.next, step.stepData)
    }

    /**
     * Confirms a Web-channel QR login from this authenticated App channel (docs/04-orchestrierung.md,
     * CONFIRM_PEER_LOGIN). The cold entry is `POST /app/channels` with that intent; both reach
     * [ConfirmPeerLoginState.Requested].
     */
    fun startPeerLogin(channelSessionId: ChannelSessionId, bindingKeyRef: String): ChannelResponse {
        val live = channelAccessGuard.requireLiveChannel(channelSessionId, bindingKeyRef)
        val channel = live.session
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState(Text("Channel must be AUTHENTICATED to confirm a peer login"))
        }
        // A process access (one-time password) is signed in without an account and serves only its process.
        if (channel.accountId == null) throw OrchestratorException.invalidState(Text("Nur mit einem Konto moeglich"))

        val step = journeyService.start(
            live, AuthIntent.CONFIRM_PEER_LOGIN,
            seed = ConfirmPeerLoginState.Requested(startedAuthenticated = true)
        )
        return responseAssembler.respond(sessionManagementService.reloadChannelSession(channelSessionId), step.next, step.stepData)
    }

    /**
     * Starts account deletion: a yes/no confirmation, then a fresh re-proof of an active factor,
     * then the deletion (docs/05-api.md #2, "Konto löschen").
     */
    fun startDeleteAccount(channelSessionId: ChannelSessionId, bindingKeyRef: String): ChannelResponse {
        val live = channelAccessGuard.requireLiveChannel(channelSessionId, bindingKeyRef)
        val channel = live.session
        if (channel.state != ChannelState.AUTHENTICATED) {
            throw OrchestratorException.invalidState(Text("Channel must be AUTHENTICATED to delete the account"))
        }
        // A process access (one-time password) is signed in without an account and serves only its process.
        if (channel.accountId == null) throw OrchestratorException.invalidState(Text("Nur mit einem Konto moeglich"))

        val step = journeyService.start(live, AuthIntent.DELETE_ACCOUNT)
        return responseAssembler.respond(sessionManagementService.reloadChannelSession(channelSessionId), step.next, step.stepData)
    }

    /** The user's answer to what the current step waits on instead of a tool run. */
    fun answer(channelSessionId: ChannelSessionId, bindingKeyRef: String, answer: String): ChannelResponse {
        val channel = channelAccessGuard.requireLiveChannel(channelSessionId, bindingKeyRef)
        val active = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState(Text("No active journey for this channel"))
        val step = journeyService.answer(active, channel, answer)
        return responseAssembler.respond(sessionManagementService.reloadChannelSession(channelSessionId), step.next, step.stepData)
    }

    private fun currentEvidence(channel: ChannelSession): SessionEvidence =
        channel.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) }?.toCoreEvidence() ?: SessionEvidence(emptyList())

    /**
     * What else this device is known by: for every key-bound credential of [accountId] on
     * [bindingKeyRef], the reference its method discloses ([ToolDescriptor.instanceDisclosure]).
     * Generic: no method name appears here. Resolved by `(method, KNOWN_ACCOUNT_AUTH)`, as in
     * `credentialsLivingOn`, since the method name alone would also match an enrollment tool.
     */
    private fun boundCredentials(accountId: AccountId, bindingKeyRef: String): List<BoundCredentialView> =
        accountService.findAccount(accountId)?.activeAuthenticationMethods.orEmpty().mapNotNull { instance ->
            val descriptor = toolRegistry.descriptors()
                .firstOrNull { it.role == ToolRole.KNOWN_ACCOUNT_AUTH && it.method == instance.method }
                ?: return@mapNotNull null
            if (descriptor.keyBinding?.livesOn(instance.details, bindingKeyRef) != true) return@mapNotNull null
            descriptor.instanceDisclosure?.referenceOf(instance.details)
                ?.let { BoundCredentialView(method = instance.method, reference = it) }
        }

    companion object {
        // Only until AUTHENTICATED; from then on the channel lives as long as its session (ADR-43).
        // The device's long-lived identity lives in DeviceAccountLink.
        private val CHANNEL_TTL: Duration = Duration.ofHours(24)
    }
}
