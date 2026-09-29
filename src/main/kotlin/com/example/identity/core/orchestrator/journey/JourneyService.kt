package com.example.identity.core.orchestrator.journey

import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.core.orchestrator.session.id
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.DemoStepReason
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.JourneyLifecycle
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.contract.texts.Text
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.journey.state.AnswerableState
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.journey.state.ToolRef
import com.example.identity.contract.tool_api.envelope.JourneyDebugStep
import com.example.identity.contract.tool_api.envelope.Next
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.core.orchestrator.session.ChannelSessionEndedException
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.session.LiveChannel
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceService
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.session.forLog
import com.example.identity.contract.tool_api.StepData

/**
 * Drives a journey between the [IntentStrategy] SPI and the rest of the orchestrator. A strategy
 * decides in pure values. Everything with side effects happens once, for every intent alike, so no
 * intent can forget the ACR cap or skip the change log.
 *
 * Each transition passes four phases (docs/04-orchestrierung.md #5, "Die vier Phasen"):
 *
 * | Phase | Who | What |
 * |---|---|---|
 * | read | [JourneyContextFactory] | assembles the read-only [JourneyContext] a strategy decides on |
 * | decide | [IntentStrategy] | turns (state, event, ctx) into a [Transition] |
 * | act | [JourneyActionExecutor] | executes the [Action] a [Transition.Perform] carries |
 * | route | [JourneyRouting] | derives `next`/[Step] from the resulting state |
 *
 * This class owns what none of the four can own alone: the journey lifecycle, the transition loop,
 * the attempt budget and the cancellation fallout. The channel's Keycloak session is
 * [ChannelSessionLifecycle]'s; what a lost session means for the journeys is decided here.
 */
@Service
// A channel ended because its Keycloak session did must stay ended (ADR-43).
@Transactional(noRollbackFor = [ChannelSessionEndedException::class])
class JourneyService(
    private val journeyRepository: AuthJourneyRepository,
    private val codec: JourneyStateCodec,
    strategies: List<IntentStrategy<*>>,
    private val accountService: AccountService,
    private val sessionManagementService: SessionManagementService,
    private val routing: JourneyRouting,
    private val contextFactory: JourneyContextFactory,
    private val actionExecutor: JourneyActionExecutor,
    private val journeyTraceService: JourneyTraceService,
    private val journeyTraceDetails: JourneyTraceDetails,
    private val journeyRecorder: JourneyRecorder,
    private val sessionLifecycle: ChannelSessionLifecycle,
    private val accountDeletionService: AccountDeletionService,
    private val clock: Clock,
) {
    private val strategiesByIntent: Map<AuthIntent, IntentStrategy<*>> = strategies.associateBy { it.intent }

    init {
        val missing = AuthIntent.entries.filterNot { it in strategiesByIntent }
        check(missing.isEmpty()) { "No IntentStrategy registered for: $missing" }
    }

    // Lifecycle ---------------------------------------------------------------

    /**
     * Starts a journey and produces its first offer. [seed] names the concrete wish (a step-up
     * target, a method to remove); without it [IntentStrategy.initialState] applies. [seedAction]
     * is a logged entry transition that runs before `initialState()`, so the strategy already sees
     * its effect (docs/04-orchestrierung.md #5, "RestoreData als erster Übergang").
     * Only on a [LiveChannel]: an ended channel never gets a journey again (docs/invarianten.md I-1).
     */
    fun start(
        channel: LiveChannel,
        intent: AuthIntent,
        seed: JourneyState? = null,
        seedAction: Action? = null
    ): Step {
        keepSessionAlive(channel)
        return startJourney(channel.session, intent, seed, parentJourneyId = null, seedAction = seedAction)
    }

    private fun startJourney(
        channel: ChannelSession,
        intent: AuthIntent,
        seed: JourneyState?,
        parentJourneyId: UUID?,
        seedAction: Action?
    ): Step {
        // One running journey per channel (docs/invarianten.md I-3). A new top-level journey
        // replaces the whole running chain. A sub-journey is the legitimate exception: its parent
        // is suspended, not started.
        if (parentJourneyId == null) {
            findActive(channel.id)?.let { cancelChain(it.entity, channel) }
        }
        val now = clock.instant()
        val journey = AuthJourney(channel.channelSessionId, intent, now.plus(JOURNEY_TTL), now)
        journey.accountId = channel.accountId
        journey.parentJourneyId = parentJourneyId
        // A step-up shows on the channel, direct or as a precondition. Only on a logged-in channel:
        // STEP_UP_IN_PROGRESS promises that cancelling leads back to AUTHENTICATED, which a cold
        // entry (a peer-login confirmation on a fresh channel) never was.
        if (intent == AuthIntent.STEP_UP && channel.state == ChannelState.AUTHENTICATED) {
            channel.state = ChannelState.STEP_UP_IN_PROGRESS
            sessionManagementService.updateChannelSession(channel)
        }
        val strategy = strategyFor(intent)
        // Placeholder for the NOT NULL state columns. It is rewritten after seedAction has run, so
        // no strategy observes it.
        codec.write(journey, seed ?: strategy.initialState(contextFactory.contextFor(journey, channel)))
        journeyRepository.save(journey) // journeyId exists from here on, for seedAction's own logging/events.

        seedAction?.let { action ->
            // Anfangs-Übergang: von keiner Strategie entschieden, aber wie jeder Übergang geloggt.
            journeyTraceService.record(channel.forLog(), journey.forLog(), "Entry", detail = journeyTraceDetails.actionDetail(action, journey, channel))
            actionExecutor.perform(journey, channel, action)
            // Re-derive, so initialState() sees the seed's effect (e.g. restored evidence).
            codec.write(journey, seed ?: strategy.initialState(contextFactory.contextFor(journey, channel)))
            journeyRepository.save(journey)
        }

        return advance(journey, channel, JourneyEvent.Started)
    }

    /** Starts STEP_UP toward [targetAcr], entered directly by the App channel's step-up trigger. */
    fun startTowardAcr(channel: LiveChannel, targetAcr: AcrLevel, startingAcr: AcrLevel): Step =
        start(channel, AuthIntent.STEP_UP, seed = StepUpState.forSubJourney(targetAcr, startingAcr))

    /**
     * Starts or restarts the intent this channel was entered with. The intent lives on the channel
     * so neither resume nor cancel has to guess it from leftover state. [seedAction] as in [start].
     */
    fun startEntryJourney(channel: LiveChannel, seedAction: Action? = null): Step = startEntryJourney(channel.session, seedAction)

    private fun startEntryJourney(channel: ChannelSession, seedAction: Action?): Step {
        // REGISTERING is never stored: it shows an account still being set up (ChannelState.shownWith).
        if (channel.state != ChannelState.AUTHENTICATED) {
            channel.state = ChannelState.ANONYMOUS
            sessionManagementService.updateChannelSession(channel)
        }
        return startJourney(channel, channel.entryIntent, seed = null, parentJourneyId = null, seedAction = seedAction)
    }

    /** The running journey of this channel, if any. A suspended parent is not it. */
    fun findActive(channelSessionId: UUID): RunningJourney? =
        journeyRepository
            .findFirstByChannelSessionIdAndLifecycleOrderByCreatedAtDesc(channelSessionId, JourneyLifecycle.STARTED)
            ?.let { RunningJourney.of(it, clock.instant()) }

    /**
     * The journey [journeyId] names, but only while it runs. A tool session outlives its journey's
     * end (logout, cancel, a finished sub-journey) - through this lookup it can no longer reach it.
     */
    fun findRunning(journeyId: UUID): RunningJourney? =
        journeyRepository.findByIdOrNull(journeyId)?.let { RunningJourney.of(it, clock.instant()) }

    /**
     * Debug view of the running journey chain ([JourneyDebugStep]): the active journey and its
     * suspended ancestors, outermost first. Empty once nothing is running.
     */
    fun debugChain(channel: ChannelSession): List<JourneyDebugStep> {
        val channelSessionId = channel.channelSessionId ?: return emptyList()
        val innermost = findActive(channelSessionId)?.entity ?: return emptyList()
        val chain = mutableListOf(innermost)
        var current = innermost
        while (true) {
            val parent = current.parentJourneyId?.let { journeyRepository.findByIdOrNull(it) } ?: break
            chain.add(parent)
            current = parent
        }
        val availableTools = routing.availableToolsOf(channel)
        // Only the innermost journey offers a step. A suspended parent waits on its sub-journey.
        val innermostNote = DemoStepReason.explain(codec.read(innermost), availableTools)
        return chain.reversed().map {
            JourneyDebugStep(
                journeyId = it.journeyId.toString(),
                intent = it.requireIntent().name,
                lifecycle = it.lifecycle.name,
                stateType = it.stateType!!,
                note = if (it == innermost) innermostNote else null,
                purpose = DemoStepReason.purpose(codec.read(it))
            )
        }
    }

    /** Cancellation by the user, unlike an exhausted budget. It ends the whole chain ([cancelChain]). */
    fun cancel(journey: RunningJourney, channel: LiveChannel) = cancelChain(journey.entity, channel.session)

    private fun cancelJourney(journey: AuthJourney, channel: ChannelSession) {
        markCancelled(journey, channel)
        fallBack(channel)
    }

    /** Ends the channel's login and with it its Keycloak session ([ChannelSessionLifecycle.end]). */
    fun endSession(channel: LiveChannel, finalState: ChannelState) = sessionLifecycle.end(channel.session, finalState)

    /** Cancels [running] and every ancestor suspended for it, so nothing of the chain is left waiting. */
    private fun cancelChain(running: AuthJourney, channel: ChannelSession) {
        markCancelledChain(running, channel)
        fallBack(channel)
    }

    private fun markCancelledChain(running: AuthJourney, channel: ChannelSession) {
        markCancelled(running, channel)
        var parentId = running.parentJourneyId
        while (parentId != null) {
            val parent = journeyRepository.findByIdOrNull(parentId) ?: break
            if (parent.lifecycle != JourneyLifecycle.SUSPENDED) break
            markCancelled(parent, channel)
            parentId = parent.parentJourneyId
        }
    }

    private fun markCancelled(journey: AuthJourney, channel: ChannelSession) {
        journey.cancel()
        // Flushed: a parent resumed or a new journey started next must not meet this one still
        // STARTED in the database (ux_journey_running_per_channel).
        journeyRepository.saveAndFlush(journey)
        journeyTraceService.record(channel.forLog(), journey.forLog(), "CANCELLED", journeyState = codec.read(journey)::class.simpleName)
    }

    // Routing -----------------------------------------------------------------

    /**
     * `next` as a pure function of the state (docs/04-orchestrierung.md #4). [JourneyState.activatable]
     * decides both what may be activated and where the client goes, so the two cannot disagree.
     */
    fun nextOf(journey: RunningJourney, channel: ChannelSession): Next = nextOf(journey.entity, channel)

    private fun nextOf(journey: AuthJourney, channel: ChannelSession): Next = routing.nextFor(codec.read(journey), routing.availableToolsOf(channel))

    /** The complete current step, including selection options and prompts. */
    fun stepOf(journey: RunningJourney, channel: ChannelSession): Step =
        routing.stepFor(codec.read(journey.entity), channel)

    // Tool interaction ---------------------------------------------------------

    /**
     * Claims [toolSessionId] as the current attempt for [tool]. Rejects anything the current state
     * does not offer. So LOGIN_LOOKUP cannot be talked into an identification: none of its states
     * lists one.
     */
    fun activate(running: RunningJourney, live: LiveChannel, tool: ToolDescriptor, toolSessionId: UUID) {
        keepSessionAlive(live)
        val journey = running.entity
        val channel = live.session
        val state = codec.read(journey)
        if (tool.toolId !in state.activatable(routing.availableToolsOf(channel))) {
            throw OrchestratorException.invalidState(Text("This tool is not offered in the current step"), "toolId=${tool.toolId}")
        }
        // A duplicate activation mints its own ToolSession too. The last one here becomes current;
        // isCurrent rejects the other.
        codec.write(journey, state.withActive(ToolRef(tool.toolId, toolSessionId, tool.startStep)))
        journeyRepository.save(journey)
        journeyTraceService.record(channel.forLog(), journey.forLog(), "TOOL_ACTIVATED", journeyState = state::class.simpleName, detail = mapOf("toolId" to tool.toolId))
    }

    /** See [JourneyActionExecutor.matchesAttestedIdentity]. */
    fun matchesAttestedIdentity(journey: RunningJourney, channel: ChannelSession, personId: String): Boolean =
        actionExecutor.matchesAttestedIdentity(journey.entity, channel, personId)

    /**
     * `active` stays in the stored state of an ended journey. [RunningJourney] ensures such a
     * journey is never asked, so no lifecycle check is needed here.
     */
    fun isCurrent(journey: RunningJourney, toolId: ToolId, toolSessionId: UUID): Boolean =
        codec.read(journey.entity).active?.let { it.toolId == toolId && it.toolSessionId == toolSessionId } ?: false

    fun applyOutcome(
        running: RunningJourney,
        channel: LiveChannel,
        tool: ToolDescriptor,
        outcome: ToolOutcome
    ): Step {
        keepSessionAlive(channel)
        return applyOutcome(running.entity, channel.session, tool, outcome)
    }

    private fun applyOutcome(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: ToolDescriptor,
        outcome: ToolOutcome
    ): Step = when (outcome) {
        is ToolOutcome.InProgress -> {
            val state = codec.read(journey)
            val active = checkNotNull(state.active) { "InProgress without an active tool" }
            codec.write(journey, state.withActive(active.copy(step = outcome.nextStep)))
            journeyRepository.save(journey)
            Step(Next.tool(tool.toolId.value, outcome.nextStep, active.toolSessionId), outcome.stepData, outcome.demo)
        }

        is ToolOutcome.Failed -> chargeAttempt(journey, channel, tool, outcome)

        // The strategy decides what the outcome means; applyTransition executes the resulting Action.
        is ToolOutcome.Completed -> advance(journey, channel, JourneyEvent.Completed(tool, outcome))
    }

    /**
     * "Zurück": the running tool ends without being declined, and the selection page returns with
     * every candidate, the abandoned one included. Whoever goes back wants to choose again. The
     * strategy is not asked. Only an [OfferingState] has a selection page; elsewhere this is [abandon].
     */
    fun back(running: RunningJourney, live: LiveChannel, tool: ToolDescriptor): Step {
        keepSessionAlive(live)
        val journey = running.entity
        val channel = live.session
        val state = codec.read(journey)
        if (state !is OfferingState) return abandon(running, live, tool)
        val cleared = state.withOffer(state.offer.withActive(null))
        codec.write(journey, cleared)
        journeyRepository.save(journey)
        journeyTraceService.record(channel.forLog(), journey.forLog(), "Back",
            journeyState = state::class.simpleName, detail = mapOf("tool" to tool.toolId.value))
        return routing.selectionFor(cleared, channel)
    }

    /** "Anderes Verfahren": the tool is declined, and the state decides whether anything is left. */
    fun abandon(running: RunningJourney, live: LiveChannel, tool: ToolDescriptor): Step {
        keepSessionAlive(live)
        val journey = running.entity
        val channel = live.session
        val state = codec.read(journey)
        codec.write(journey, state.withActive(null))
        journeyRepository.save(journey)
        return advance(journey, channel, JourneyEvent.Abandoned(tool))
    }

    /**
     * An answer to what the current [AnswerableState] waits on, instead of a tool run. Valid values
     * and their meaning belong to the state and its strategy, not to this entry point.
     */
    fun answer(running: RunningJourney, live: LiveChannel, answer: String): Step {
        keepSessionAlive(live)
        val journey = running.entity
        val channel = live.session
        val state = codec.read(journey)
        if (state !is AnswerableState) {
            throw OrchestratorException.invalidState(Text("Nothing is currently waiting for an answer"))
        }
        return advance(journey, channel, JourneyEvent.Answered(answer))
    }

    /**
     * Syncs [source]'s evidence into a running journey (docs/05-api.md Abschnitt 3). This is no tool
     * outcome and knows nothing about Keycloak; the caller names [source]. [updates] is the complete
     * currently valid set: a method missing here has expired and is dropped. So this runs on every
     * report. Restored evidence on a fresh channel goes through [start]'s `seedAction` instead.
     */
    fun applyEvidenceUpdate(running: RunningJourney, live: LiveChannel, source: String, updates: List<MethodEvidence>) {
        val journey = running.entity
        val channel = live.session
        journeyRecorder.mergeEvidence(journey, channel, source, updates)
        advance(journey, channel, JourneyEvent.EvidenceReported)
    }

    // Transitions ----------------------------------------------------------------

    private fun advance(journey: AuthJourney, channel: ChannelSession, event: JourneyEvent): Step {
        val strategy = strategyFor(journey.requireIntent())
        val state = codec.read(journey)
        val ctx = contextFactory.contextFor(journey, channel)
        val transition = strategy.transitionErased(state, event, ctx)
        // EvidenceReported fires on every upsertChannel, also for a pure re-send of the full set
        // (docs/05-api.md Abschnitt 3). Logging it each time would repeat the same entry on every
        // poll. A real proof always leads to a different state, so nothing real is suppressed.
        val isNoOpEvidenceUpdate = event is JourneyEvent.EvidenceReported && transition is Transition.To && transition.state == state
        if (!isNoOpEvidenceUpdate) {
            // The log shows what routing itself derives, so nextFor stays the one routing authority.
            val availableTools = routing.availableToolsOf(channel)
            journeyTraceService.record(channel.forLog(), journey.forLog(), event::class.simpleName!!,
                journeyState = state::class.simpleName,
                // acrFloor is what this step was judged against. resolvedAcr is the combined level
                // of all evidence; a Completed entry's achievedAcr shows only that one tool's
                // ceiling, so two loa1 factors reaching loa2 would otherwise not show.
                detail = journeyTraceDetails.eventDetail(event) +
                    journeyTraceDetails.transitionDetail(transition, journey, channel, state, availableTools) { target ->
                        routing.nextFor(target, availableTools)
                    } +
                    state.logDetail +
                    mapOf("acrFloor" to ctx.acrFloor, "resolvedAcr" to ctx.policy.resolveAcr(ctx.evidence, ctx.account))
            )
        }
        return applyTransition(journey, channel, transition)
    }

    private fun applyTransition(journey: AuthJourney, channel: ChannelSession, transition: Transition): Step = when (transition) {
        is Transition.To -> {
            codec.write(journey, transition.state)
            journeyRepository.save(journey)
            routing.stepFor(transition.state, channel)
        }
        is Transition.RequireSubJourney -> suspendFor(journey, channel, transition)
        is Transition.Authenticated -> finish(journey, channel)
        // The one recursive step (advance -> applyTransition): see perform().
        is Transition.Perform -> perform(journey, channel, transition)
        Transition.Logout -> logOut(journey, channel)
        is Transition.Cancel -> cancelToParentOrEntry(journey, channel)
        is Transition.Abort -> {
            journey.fail()
            journeyRepository.save(journey)
            throw OrchestratorException.processAborted(transition.reason)
        }
    }

    /**
     * The wish stays parked as this journey's state. SUSPENDED plus the child's parentJourneyId
     * keeps "one running journey per channel" true.
     */
    private fun suspendFor(journey: AuthJourney, channel: ChannelSession, transition: Transition.RequireSubJourney): Step {
        codec.write(journey, transition.resumeWith)
        journey.lifecycle = JourneyLifecycle.SUSPENDED
        // Flushed before the child row: the database allows one STARTED journey per channel
        // (ux_journey_running_per_channel), and the child insert would otherwise come first.
        journeyRepository.saveAndFlush(journey)
        return startJourney(channel, transition.intent, seed = transition.seedWith, parentJourneyId = journey.journeyId, seedAction = null)
    }

    /** Resumes at resumeState against a fresh context, because the action has just changed what the old one showed. */
    private fun perform(journey: AuthJourney, channel: ChannelSession, transition: Transition.Perform): Step {
        val demoNotice = actionExecutor.perform(journey, channel, transition.action)
        codec.write(journey, transition.resumeState)
        val step = advance(journey, channel, JourneyEvent.ActionCompleted)
        return if (demoNotice == null) step else step.copy(demo = step.demo.orEmpty() + demoNotice)
    }

    private fun logOut(journey: AuthJourney, channel: ChannelSession): Step {
        journey.consume(clock.instant())
        journeyRepository.save(journey)
        journeyTraceService.record(channel.forLog(), journey.forLog(), "LOGGED_OUT", journeyState = "LoggedOut")
        sessionLifecycle.end(channel, ChannelState.LOGGED_OUT)
        return Step(next = null)
    }

    /**
     * A sub-journey that gave up hands back to its suspended parent, as in [finish]. In a top-level
     * journey nothing left to offer equals an explicit cancel; a channel back on AUTHENTICATED has
     * nothing to restart.
     */
    private fun cancelToParentOrEntry(journey: AuthJourney, channel: ChannelSession): Step {
        val parent = journey.parentJourneyId?.let { journeyRepository.findByIdOrNull(it) }
        if (parent != null && parent.lifecycle == JourneyLifecycle.SUSPENDED) {
            markCancelled(journey, channel)
            parent.lifecycle = JourneyLifecycle.STARTED
            journeyRepository.save(parent)
            return advance(parent, channel, JourneyEvent.SubJourneyCancelled(journey.requireIntent()))
        }
        cancelJourney(journey, channel)
        return if (channel.state == ChannelState.AUTHENTICATED) Step(Next.AUTHENTICATED) else startEntryJourney(channel, seedAction = null)
    }

    private fun finish(journey: AuthJourney, channel: ChannelSession): Step {
        journey.consume(clock.instant())
        // Flushed before a suspended parent resumes (ux_journey_running_per_channel).
        journeyRepository.saveAndFlush(journey)
        val parent = journey.parentJourneyId?.let { journeyRepository.findByIdOrNull(it) }
            ?.takeIf { it.lifecycle == JourneyLifecycle.SUSPENDED }
        // Before the sign-in log: a login whose session is gone is no sign-in.
        if (parent == null) openLoginSession(channel)
        // Before the parent handoff: a step-up that a parent journey waited on is a step-up too.
        journeyRecorder.recordSignIn(journey, channel, contextFactory.currentAcrOf(channel))

        if (parent != null) {
            // The parent picks up exactly where it was parked, so the original wish survives.
            parent.lifecycle = JourneyLifecycle.STARTED
            journeyRepository.save(parent)
            return advance(parent, channel, JourneyEvent.SubJourneyFinished(journey.requireIntent(), contextFactory.currentAcrOf(channel)))
        }

        channel.state = ChannelState.AUTHENTICATED
        sessionManagementService.updateChannelSession(channel)
        return Step(Next.AUTHENTICATED)
    }

    /** AUTHENTICATED and the Keycloak session come together (ADR-43). */
    private fun openLoginSession(channel: ChannelSession) {
        sessionLifecycle.open(channel)?.let { endWithSession(channel, it.cause) }
    }

    /** Every journey interaction may renew the session. Cancelling is no interaction: it also runs on a Keycloak sign-out. */
    private fun keepSessionAlive(live: LiveChannel) {
        sessionLifecycle.keepAlive(live.session)?.let { endWithSession(live.session, it.cause) }
    }

    /** The channel's Keycloak session is gone, so the channel ends with it; this commits (ADR-43). */
    private fun endWithSession(channel: ChannelSession, cause: RuntimeException): Nothing {
        findActive(channel.id)?.let { markCancelledChain(it.entity, channel) }
        journeyTraceService.recordForChannel(channel.forLog(), "EXPIRED")
        sessionLifecycle.end(channel, ChannelState.EXPIRED)
        throw ChannelSessionEndedException("Channel ${channel.id} ended with its session: ${cause.message}")
    }


    /**
     * The attempt budget spans the whole journey (docs/04-orchestrierung.md #7). A failed attempt
     * with budget left behaves like missing input, not like an HTTP error. An exhausted budget ends
     * the journey, not just the current state.
     */
    private fun chargeAttempt(journey: AuthJourney, channel: ChannelSession, tool: ToolDescriptor, outcome: ToolOutcome.Failed): Step {
        journey.attemptBudget -= 1
        journeyTraceService.record(channel.forLog(), journey.forLog(), "TOOL_FAILED",
            journeyState = codec.read(journey)::class.simpleName,
            detail = mapOf(
                "toolId" to tool.toolId,
                "reason" to outcome.reason,
                "attemptedAccountId" to ((outcome as? ToolOutcome.Failed.LookupAuth)?.attempted as? Attempted.Account)?.id,
                "attemptedPersonId" to (outcome as? ToolOutcome.Failed.Identification)?.attemptedPersonId,
                "attemptBudgetLeft" to journey.attemptBudget
            )
        )
        if (journey.attemptBudget <= 0) {
            journey.fail()
            journeyRepository.save(journey)
            throw OrchestratorException.processAborted(Text("Retry-Limit erreicht: {reason}", "reason" to outcome.reason))
        }
        journeyRepository.save(journey)
        return Step(nextOf(journey, channel), FailedAttemptStep(outcome.reason))
    }

    // Cancellation fallout -------------------------------------------------------

    /**
     * After an abandoned journey the channel returns to its previous login status
     * ([ChannelState.isLoggedIn]), one rule for every intent. Otherwise a cold step-up could claim
     * AUTHENTICATED without proof (docs/invarianten.md I-4). The account is re-derived from the
     * device link, which survives a journey; an AuthContext does not.
     */
    private fun fallBack(channel: ChannelSession) {
        val target = if (channel.state?.isLoggedIn == true) ChannelState.AUTHENTICATED else ChannelState.ANONYMOUS
        channel.state = target
        if (target != ChannelState.AUTHENTICATED) {
            channel.authContextId = null
            channel.authEvidenceId = null
            val abandonedAccountId = channel.accountId
            channel.subject = if (channel.entryIntent.startsFromDeviceLink && channel.bindingKeyRef != null) {
                sessionManagementService.findLinkedAccountId(channel.bindingKeyRef!!)?.let(Subject::Account)
            } else {
                null
            }
            discardIfBeingSetUp(abandonedAccountId)
        }
        sessionManagementService.updateChannelSession(channel)
    }

    /**
     * An account still being set up goes with its abandoned registration, as a whole (ADR-46):
     * with its methods, so nobody logs into a half-registered account and its address is free
     * again. A registered account stays. The channel no longer points to it at this point.
     */
    private fun discardIfBeingSetUp(accountId: Long?) {
        if (accountId == null || !accountService.isBeingSetUp(accountId)) return
        accountDeletionService.deleteAccount(accountId)
    }

    private fun strategyFor(intent: AuthIntent): IntentStrategy<*> =
        strategiesByIntent[intent] ?: error("No IntentStrategy for $intent")

    /**
     * The one place the SPI's state type is erased. A strategy only gets the state of its own
     * intent; the `intent` column and [JourneyStateCodec.read] guarantee that. The registry is
     * heterogeneous, so the cast lives here once. The `Erased` suffix avoids reading like recursion.
     */
    @Suppress("UNCHECKED_CAST")
    private fun IntentStrategy<*>.transitionErased(state: JourneyState, event: JourneyEvent, ctx: JourneyContext): Transition =
        (this as IntentStrategy<JourneyState>).transition(state, event, ctx)

    companion object {
        private val JOURNEY_TTL: Duration = Duration.ofMinutes(60)
    }
}
