package com.example.identity.core.orchestrator.channel

import com.example.identity.core.orchestrator.session.channelType
import com.example.identity.core.orchestrator.session.id
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.account.AccountService
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.journey.JourneyService
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.core.orchestrator.domain.AmrSource
import com.example.identity.core.orchestrator.session.SessionEvidenceService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.session.toCoreEvidence
import com.example.identity.contract.tool_api.envelope.ActiveMethodView
import com.example.identity.contract.tool_api.envelope.AuthData
import com.example.identity.contract.tool_api.envelope.AuthSubject
import com.example.identity.contract.tool_api.envelope.AuthSubjectType
import com.example.identity.contract.tool_api.envelope.ChannelBlock
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.envelope.DemoInfo
import com.example.identity.contract.tool_api.envelope.DemoSession
import com.example.identity.contract.tool_api.envelope.Next
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.claims.AcrLevel
import org.springframework.transaction.annotation.Transactional
import com.example.identity.contract.tool_api.StepData
import org.springframework.stereotype.Component

/**
 * Builds the response envelope (docs/05-api.md #2) from a channel's current state: channel block,
 * next step, demo block and WEB auth data. It decides nothing, for [ChannelService] and
 * `ToolJourneyService` alike.
 */
@Component
@Transactional
class ChannelResponseAssembler(
    private val accountService: AccountService,
    private val sessionEvidenceService: SessionEvidenceService,
    private val authPolicy: AuthPolicy,
    private val journeyService: JourneyService,
    private val personDirectory: PersonDirectory,
    private val toolRegistry: ToolHandlerRegistry,
    private val demoDisclosure: DemoDisclosure,
) {

    fun respond(channel: ChannelSession, next: Next? = null, stepData: StepData? = null): ChannelResponse {
        // A terminal channel (docs/02-domaenenmodell.md #3) never has a next, whatever the caller
        // passed, so no stray next can resurrect it.
        val resolved = if (channel.state?.isTerminal == true) {
            null
        } else {
            next ?: journeyService.findActive(channel.id)?.let { journeyService.nextOf(it, channel) }
                ?: if (channel.state == ChannelState.AUTHENTICATED) Next.AUTHENTICATED else null
        }
        return ChannelResponse(
            channel = buildChannelBlock(channel, includeAccountFields = true),
            next = resolved,
            stepData = stepData,
            demo = demoInfo(channel),
            authData = authDataFor(channel)
        )
    }

    /**
     * WEB only (docs/05-api.md Abschnitt 3), `null` for APP. Every WEB response carries
     * it, including tool responses from `ToolJourneyService`.
     */
    fun authDataFor(channel: ChannelSession): AuthData? {
        if (channel.channel != ChannelType.WEB) return null
        val evidence = channel.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) }
        val amr = evidence?.currentAmr?.associateWith { evidence.currentAmrSource[it] ?: AmrSource.ORCHESTRATOR }
        val acr = evidence?.let {
            val account = channel.accountId?.let { id -> accountService.findAccount(id) }
            authPolicy.resolveAcr(it.toCoreEvidence(), account)
        }
        val subject = channel.accountId?.let { AuthSubject(AuthSubjectType.ACCOUNT, it.toString()) }
            ?: channel.invitation?.let { AuthSubject(AuthSubjectType.INVITATION, it.value) }
        return AuthData(acr = acr?.value, amr = amr, subject = subject)
    }

    /**
     * The demo journey-chain view for channel-level responses. [DemoDisclosure] assembles it, the
     * one place that decides whether demo values are disclosed at all.
     */
    private fun demoInfo(channel: ChannelSession): DemoInfo? {
        val journeys = journeyService.debugChain(channel)
        val accountId = channel.accountId
        val personId = accountId?.let { accountService.findAccount(it)?.personId }
        return demoDisclosure.assemble(accountId, personId, journeys, session = demoSession(channel))
    }

    /**
     * Who the session belongs to and what it has proven, for the demo column. Null until something
     * was proven; before that the client shows "not signed in" on its own.
     */
    fun demoSession(channel: ChannelSession): DemoSession? {
        if (!channel.hasProvenFactor) return null
        val evidence = channel.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) }
        val account = channel.accountId?.let { accountService.findAccount(it) }
        return DemoSession(
            authenticated = channel.state == ChannelState.AUTHENTICATED,
            personName = account?.personId?.let { personDirectory.displayName(it) },
            acr = evidence?.let { authPolicy.resolveAcr(it.toCoreEvidence(), account) }?.value,
            amr = evidence?.currentAmr ?: emptyList()
        )
    }

    /**
     * The channel block shared by every response (docs/05-api.md #2), public so tool controllers
     * can attach it. [includeAccountFields] gates `currentAcr`/`currentAmr`/`activeMethods`; only
     * the on-demand security summary needs them. They appear only after
     * [ChannelSession.hasProvenFactor], so a recognized device does not leak the account's methods.
     */
    /** The stored state as clients see it: ANONYMOUS with an account being set up is REGISTERING (ADR-46). */
    fun shownState(channel: ChannelSession): ChannelState =
        (channel.state ?: ChannelState.ANONYMOUS).shownWith(channel.accountId?.let { accountService.isBeingSetUp(it) } == true)

    fun buildChannelBlock(channel: ChannelSession, includeAccountFields: Boolean = false): ChannelBlock {
        val channelType = channel.channelType.name
        if (!includeAccountFields || !channel.hasProvenFactor) {
            return ChannelBlock(
                channelSessionId = channel.id,
                channelType = channelType,
                state = shownState(channel).name,
                hasProvenFactor = channel.hasProvenFactor
            )
        }
        val evidence = channel.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) }
        val account = channel.accountId?.let { accountService.findAccount(it) }
        val currentAcr = evidence?.let { authPolicy.resolveAcr(it.toCoreEvidence(), account) }
        return ChannelBlock(
            channelSessionId = channel.id,
            channelType = channelType,
            state = shownState(channel).name,
            hasProvenFactor = channel.hasProvenFactor,
            currentAcr = currentAcr?.value,
            currentAmr = evidence?.currentAmr,
            activeMethods = toActiveMethodViews(account?.activeAuthenticationMethods)
        )
    }

    /**
     * `maxAcr`/`factorTypes` come from the method's module; `enrolledUnderAcr`/`effectiveAcr` from the
     * account's enrollment record (the ADR-5 cap). So the UI can show why a method reaches less
     * than its catalog entry promises.
     */
    fun toActiveMethodViews(methods: List<AuthMethodView>?): List<ActiveMethodView> =
        methods.orEmpty().map { m ->
            val descriptor = toolRegistry.moduleOf(m.method)
            ActiveMethodView(
                id = m.id,
                method = m.method,
                label = m.label,
                factorTypes = descriptor?.factorTypes?.toList(),
                        maxAcr = descriptor?.maxAcr?.value,
                enrolledUnderAcr = m.enrolledUnderAcr,
                effectiveAcr = descriptor?.let { AcrLevel.min(AcrLevel.parse(m.enrolledUnderAcr), it.maxAcr) }?.value,
                changeable = toolRegistry.changeToolOf(m.method) != null
            )
        }
}
