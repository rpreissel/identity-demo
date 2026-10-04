package com.example.identity.core.orchestrator.channel

import com.example.identity.core.orchestrator.journey.JourneyEndedException
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.session.id
import com.example.identity.core.orchestrator.session.ToolSessionStatus
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.journey.RunningJourney
import com.example.identity.core.orchestrator.journey.JourneyService
import com.example.identity.core.orchestrator.journey.Step
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.policy.requiresSatisfied
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.ChannelSessionEndedException
import com.example.identity.core.orchestrator.session.LiveChannel
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.session.PersonLockoutService
import com.example.identity.core.orchestrator.session.AccountLockoutService
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.tool.ToolAvailabilityService
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.envelope.TOOLS_API
import com.example.identity.contract.tool_api.ToolVersion
import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.envelope.ChannelResponse
import com.example.identity.contract.tool_api.envelope.DemoInfo
import com.example.identity.contract.tool_api.envelope.Next
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolModule
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolOutcome
import java.net.URI
import java.time.Duration
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.util.UriComponentsBuilder

/**
 * Plumbing shared by every tool controller: binding check, throttling, tool-session lifecycle,
 * response envelope. The controllers stay tool-specific (docs/08-projektrahmen.md A11); only this
 * cross-cutting part is central, so it cannot diverge. Implements the [ToolJourney] port. [Context]
 * is only ever handed out as [ToolContext] and cast back here, where it is constructed. Which tool
 * may run when is not decided here but by [JourneyService].
 */
@Service
@Transactional(noRollbackFor = [ChannelSessionEndedException::class, JourneyEndedException::class]) // ADR-43, I-2, see JourneyService
class ToolJourneyService(
    private val sessionManagementService: SessionManagementService,
    private val channelAccessGuard: ChannelAccessGuard,
    private val toolRegistry: ToolHandlerRegistry,
    private val accountService: AccountService,
    private val accountLockoutService: AccountLockoutService,
    private val personLockoutService: PersonLockoutService,
    private val responseAssembler: ChannelResponseAssembler,
    private val journeyService: JourneyService,
    private val toolAvailabilityService: ToolAvailabilityService,
    private val demoDisclosure: DemoDisclosure
) : ToolJourney {
    data class Context(
        override val toolId: String,
        override val version: Int,
        override val toolSessionId: ToolSessionId,
        val journeyId: JourneyId,
        val channelSessionId: ChannelSessionId,
        override val bindingKeyRef: String,
        override val accountId: AccountId?
    ) : AuthorizedToolContext

    /** A [Context] whose session the activating request created. */
    data class Activation(val context: Context) : ActivationToolContext, AuthorizedToolContext by context

    /** The service's own data behind any context it handed out. */
    private fun ToolContext.data(): Context = when (this) {
        is Context -> this
        is Activation -> context
        else -> error("Not a context of this journey: ${this::class.simpleName}")
    }

    override fun activationLocation(context: ToolContext, baseUri: URI): URI =
        UriComponentsBuilder.fromUri(baseUri)
            .replacePath("$TOOLS_API/{toolId}/v{version}/{toolSessionId}")
            .buildAndExpand(context.toolId, context.version, context.toolSessionId)
            .toUri()

    /**
     * Mints the ToolSession and lets the journey decide whether [tool] may run. The check is
     * membership in the current offer, so a tool never offered cannot be activated by naming it.
     */
    override fun beginActivation(channelSessionId: ChannelSessionId, bindingKeyRef: String, tool: ToolVersion): Activation {
        val toolId = tool.toolId.value
        val live = channelAccessGuard.requireLiveChannel(channelSessionId, bindingKeyRef)
        val channel = live.session
        val journey = journeyService.findActive(channelSessionId)
            ?: throw OrchestratorException.invalidState(Text("No active journey for this channel"))
        val descriptor = toolRegistry.toolOf(ToolId(toolId))

        validatePreconditions(tool, channel)
        // Only for a tool whose account the channel knows (KNOWN_ACCOUNT_AUTH). ACCOUNT_LOOKUP_AUTH and IDENT
        // tools are rate-limited where they resolve their subject ([Lockouts]) and
        // answer with their ordinary failure instead of this explicit 423.
        if (descriptor.role == ToolRole.KNOWN_ACCOUNT_AUTH) {
            channel.accountId?.let { accountLockoutService.assertNotLocked(it) }
        }

        val toolSession = sessionManagementService.createToolSession(journey.journeyId, TOOL_TTL)
        journeyService.activate(journey, live, descriptor, toolSession.id)
        return Activation(Context(
            toolId = toolId,
            version = tool.version,
            toolSessionId = toolSession.id,
            journeyId = journey.journeyId,
            channelSessionId = channel.id,
            bindingKeyRef = bindingKeyRef,
            accountId = channel.accountId
        ))
    }

    /**
     * [JourneyService.activate] checks only the current offer, not a tool's own preconditions.
     * Without this, a client could activate a gated tool (enroll-password needs a confirmed email)
     * directly.
     */
    private fun validatePreconditions(tool: ToolVersion, channel: ChannelSession) {
        val toolId = tool.toolId.value
        // A direct activation is re-checked against both availability axes
        // (docs/03-tool-architektur.md), though the offer already excludes unavailable tools.
        requireDeclaredVersion(tool, channel)
        if (!toolAvailabilityService.isEnabled(toolId, checkNotNull(channel.channel))) {
            throw OrchestratorException.invalidState(Text("This tool is not available on this channel"), "toolId=${toolId}")
        }

        val descriptor = toolRegistry.toolOf(ToolId(toolId))
        val account = channel.accountId?.let { accountService.findAccount(it) }
        descriptor.requires.forEach { requirement ->
            if (!requiresSatisfied(requirement, account)) {
                throw OrchestratorException.invalidState(
                    Text("This tool requires a prior proof first"), "toolId=${toolId}, requirement=${requirement.attributeType.wireName}, minClaimTrust=${requirement.minClaimTrust}"
                )
            }
        }
    }

    /**
     * The write path: [loadContext] plus the authorization [applyOutcome]'s parameter type demands.
     * There is no other way to an [AuthorizedToolContext], so the check is structural.
     */
    override fun loadCurrent(toolSessionId: ToolSessionId, bindingKeyRef: String, tool: ToolVersion): Context {
        val context = loadContext(toolSessionId, bindingKeyRef, tool)
        requireCurrentTool(context)
        // On every attempt, not only at activation: a session opened before the lock must not keep
        // guessing, nor sign in with the right password while the account is locked (07-betrieb #4).
        if (toolRegistry.toolOf(tool.toolId).role == ToolRole.KNOWN_ACCOUNT_AUTH) {
            context.accountId?.let { accountLockoutService.assertNotLocked(it) }
        }
        return context
    }

    override fun loadContext(toolSessionId: ToolSessionId, bindingKeyRef: String, tool: ToolVersion): Context {
        val toolSession = sessionManagementService.findToolSessionById(toolSessionId)
            ?: throw OrchestratorException.notFound(Text("Tool session not found"), "toolSessionId=${toolSessionId}")
        val journey = journeyService.findRunning(toolSession.journeyId!!)
            ?: throw OrchestratorException.processGone(Text("Journey for this tool session is gone"))
        val channel = channelAccessGuard.requireChannel(journey.channelSessionId, bindingKeyRef)
        requireDeclaredVersion(tool, channel)
        return Context(
            toolId = tool.toolId.value,
            version = tool.version,
            toolSessionId = toolSessionId,
            journeyId = journey.journeyId,
            channelSessionId = channel.id,
            bindingKeyRef = bindingKeyRef,
            accountId = channel.accountId
        )
    }

    override fun findEnrollment(context: ToolContext, module: ToolModule): EnrollmentRef? =
        context.accountId?.let { accountId ->
            if (module.onePerDevice) accountService.activeInstanceEnrollment(accountId, module.method, context.bindingKeyRef)
            else accountService.activeEnrollment(accountId, module.method)
        }

    override fun requireEnrollment(context: ToolContext, module: ToolModule): EnrollmentRef {
        return findEnrollment(context, module) ?: throw UnresolvableReferenceException(
            if (module.onePerDevice) Text("Dieses Anmeldeverfahren ist auf diesem Gerät nicht eingerichtet")
            else Text("Kein aktives Anmeldeverfahren dieser Art fuer dieses Konto"),
            "no active ${module.method} method",
        )
    }

    /**
     * The channel speaks one version of each tool, the one it declared (ADR-51). A call in another
     * version is refused like a tool the channel never declared.
     */
    private fun requireDeclaredVersion(tool: ToolVersion, channel: ChannelSession) {
        if (tool.toString() !in channel.availableClientTools) {
            throw OrchestratorException.invalidState(Text("This tool is not available on this channel"), "tool=${tool}")
        }
    }

    private fun requireCurrentTool(context: ToolContext) {
        if (!isCurrentTool(context)) {
            throw OrchestratorException.invalidState(Text("This tool is not the currently active step of this journey"), "toolId=${context.toolId}")
        }
    }

    override fun isCurrentTool(context: ToolContext): Boolean {
        val ctx = context.data()
        val journey = resolveJourney(ctx)
        return journeyService.isCurrent(journey, ToolId(ctx.toolId), ctx.toolSessionId)
    }

    /**
     * "Back"/"Switch". Ends the ToolSession at once, not at its TTL: a re-offered candidate can
     * have the same toolId, so the toolId alone cannot tell old and new session apart.
     */
    override fun abandon(context: AuthorizedToolContext): ChannelResponse =
        leave(context) { journey, channel, tool -> journeyService.abandon(journey, channel, tool) }

    /** "Zurück": same session handling as [abandon], but the journey is not told the tool was declined. */
    override fun back(context: AuthorizedToolContext): ChannelResponse =
        leave(context) { journey, channel, tool -> journeyService.back(journey, channel, tool) }

    private fun leave(context: AuthorizedToolContext, move: (RunningJourney, LiveChannel, Tool) -> Step): ChannelResponse {
        val ctx = context.data()
        val journey = resolveJourney(ctx)
        val live = resolveChannel(ctx, journey)
        val channel = live.session
        sessionManagementService.endToolSession(ctx.toolSessionId, ToolSessionStatus.ABANDONED)
        val step = move(journey, live, toolRegistry.toolOf(ToolId(ctx.toolId)))
        return ChannelResponse(
            channel = responseAssembler.buildChannelBlock(channel),
            next = step.next,
            stepData = step.stepData,
            authData = responseAssembler.authDataFor(channel),
            // Like every other tool response: without it the demo column forgets whose session
            // this is as soon as the user steps back.
            demo = demoInfo(journey, channel, values = null)
        )
    }

    /**
     * Turns an outcome into a journey transition and the common response envelope (docs/05-api.md
     * #2). The context carries ids only; entities are resolved fresh in this transaction.
     */
    override fun applyOutcome(context: AuthorizedToolContext, outcome: ToolOutcome): ChannelResponse {
        val ctx = context.data()
        val journey = resolveJourney(ctx)
        val live = resolveChannel(ctx, journey)
        val channel = live.session
        val descriptor = toolRegistry.toolOf(ToolId(ctx.toolId))
        chargeRateLimits(channel.accountId, channel.channel?.name, descriptor, "${ctx.toolId}@${ctx.version}", outcome)
        // A completed tool is done for good, even while the journey still names it as active.
        if (outcome is ToolOutcome.Completed) sessionManagementService.endToolSession(ctx.toolSessionId, ToolSessionStatus.DONE)

        val step = journeyService.applyOutcome(journey, live, descriptor, outcome)

        // step.demo is a field of its own and never mixes with stepData (docs/05-api.md #2).
        return ChannelResponse(
            channel = responseAssembler.buildChannelBlock(channel),
            next = step.next,
            stepData = step.stepData,
            demo = demoInfo(journey, channel, step.demo),
            authData = responseAssembler.authDataFor(channel)
        )
    }

    override fun matchesAttestedIdentity(context: AuthorizedToolContext, personId: PartnerNumber): Boolean {
        val ctx = context.data()
        val journey = resolveJourney(ctx)
        return journeyService.matchesAttestedIdentity(journey, resolveChannel(ctx, journey).session, personId)
    }

    /**
     * Charges the brute-force counter matching what this tool attempted. A failure names its
     * subject by its variant ([ToolOutcome.Failed]); a success resets the same counter. A lookup
     * login's subject comes from the outcome, since the channel's account is bound only later.
     */
    private fun chargeRateLimits(channelAccountId: AccountId?, channelType: String?, descriptor: Tool, toolVersion: String, outcome: ToolOutcome) {
        when (outcome) {
            is ToolOutcome.InProgress -> Unit

            // An outcome of another role's kind would charge the wrong counter or take the wrong
            // action: a contract error of the tool module, not a user error.
            is ToolOutcome.Failed -> {
                check(outcome.fits(descriptor.role)) {
                    "${descriptor.toolId} (${descriptor.role}) answered with ${outcome::class.simpleName}"
                }
                when (outcome) {
                    is ToolOutcome.Failed.KnownAccountAuth -> channelAccountId?.let { accountLockoutService.recordFailure(it, channelType, descriptor.method, toolVersion) }
                    is ToolOutcome.Failed.AccountLookupAuth -> when (val attempted = outcome.attempted) {
                        is Attempted.Account -> accountLockoutService.recordFailure(attempted.id, channelType, descriptor.method, toolVersion)
                        // A one-time password belongs to a person, like a Freischaltcode.
                        is Attempted.Person -> personLockoutService.recordFailure(attempted.id)
                        null -> Unit
                    }
                    // A guessed Freischaltcode or PIN is a credential guess, and success adopts the
                    // person's account outright.
                    is ToolOutcome.Failed.Identification -> outcome.attemptedPersonId?.let { personLockoutService.recordFailure(it) }
                    // Nothing of an existing account was guessed; the ToolSession's own limits bound it.
                    is ToolOutcome.Failed.NothingGuessed -> Unit
                }
            }

            is ToolOutcome.Completed -> {
                check(outcome.fits(descriptor.role)) {
                    "${descriptor.toolId} (${descriptor.role}) answered with ${outcome::class.simpleName}"
                }
                checkStaysWithin(descriptor, outcome)
                when (outcome) {
                    is ToolOutcome.Completed.Authenticated -> ((outcome.subject as? Subject.Account)?.id ?: channelAccountId)?.let { accountLockoutService.recordSuccess(it) }
                    is ToolOutcome.Completed.Identified -> outcome.personId?.let { personLockoutService.recordSuccess(it) }
                    // Nothing was guessed, nothing to reset.
                    is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Attested, is ToolOutcome.Completed.Approved -> Unit
                }
            }
        }
    }

    /** For GET: only the still-current tool's rebuilt InProgress state is shown. */
    override fun buildReadResponse(context: ToolContext, freshOutcome: ToolOutcome.InProgress?): ChannelResponse {
        val ctx = context.data()
        val journey = resolveJourney(ctx)
        val channel = resolveChannel(ctx, journey).session
        val next = if (freshOutcome != null) {
            Next.tool(ctx.toolId, freshOutcome.nextStep, ctx.toolSessionId)
        } else {
            journeyService.nextOf(journey, channel)
        }
        // A resumed tool (e.g. after a reload) still gets the demo hints of its activation: this
        // GET is the only response a resume sees.
        return ChannelResponse(
            channel = responseAssembler.buildChannelBlock(channel),
            next = next,
            stepData = freshOutcome?.stepData,
            demo = demoInfo(journey, channel, freshOutcome?.demo),
            authData = responseAssembler.authDataFor(channel)
        )
    }

    /** Only a running journey; a finished one takes no more tool results (docs/invarianten.md I-2). */
    private fun resolveJourney(context: Context): RunningJourney =
        journeyService.findRunning(context.journeyId)
            ?: throw OrchestratorException.processGone(Text("Journey for this tool session is gone"))

    /** LOGGED_OUT/EXPIRED is final (docs/02-domaenenmodell.md #3): no tool may move it ([LiveChannel]). */
    private fun resolveChannel(context: Context, journey: RunningJourney): LiveChannel {
        val journeyChannelId = journey.channelSessionId
        if (journeyChannelId != context.channelSessionId) {
            throw OrchestratorException.invalidState(Text("Tool context no longer matches its journey channel"))
        }
        return channelAccessGuard.requireLiveChannel(journeyChannelId, context.bindingKeyRef)
    }

    /** The demo block for tool responses, assembled by [DemoDisclosure]. personId is read off the account. */
    private fun demoInfo(journey: RunningJourney, channel: ChannelSession, values: Map<String, Any?>?): DemoInfo? {
        val journeys = journeyService.debugChain(channel)
        val personId = channel.accountId?.let { accountService.findAccount(it)?.personId }
        return demoDisclosure.assemble(
            accountId = channel.accountId,
            personId = personId,
            journeys = journeys,
            values = values,
            // An authenticated channel gets the block even when empty: the client's completed view
            // reads accountId/personId from it.
            includeWhenEmpty = channel.state == ChannelState.AUTHENTICATED,
            session = responseAssembler.demoSession(channel)
        )
    }

    companion object {
        private val TOOL_TTL: Duration = Duration.ofMinutes(10)
    }
}

/**
 * A run that proves more than its descriptor declares is a contract error of the tool module: the
 * excess would flow unchecked into the session's evidence and the claims (docs/03-tool-architektur.md).
 */
internal fun checkStaysWithin(tool: Tool, outcome: ToolOutcome.Completed) =
    check(tool.staysWithin(outcome)) {
        "${tool.toolId} reported ${tool.levelOf(outcome)}/${tool.factorsOf(outcome)}, " +
            "beyond its declaration's ${tool.maxAcr}/${tool.factorTypes}"
    }
