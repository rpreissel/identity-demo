package com.example.identity.core.orchestrator.journey

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.core.orchestrator.session.id
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.JourneyStart
import com.example.identity.core.orchestrator.domain.journey.JourneyWithoutInput
import com.example.identity.core.orchestrator.domain.journey.StartingAccount
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.Answer
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.JourneyLifecycle
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.contract.texts.Text
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.domain.journey.state.AnswerableState
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.ToolRef
import com.example.identity.contract.tool_api.envelope.JourneyDebugStep
import com.example.identity.contract.tool_api.envelope.Next
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.session.AccountDeletionService
import com.example.identity.core.orchestrator.session.ChannelSessionEndedException
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.appOpening
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.session.LiveChannel
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceService
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import com.example.identity.core.orchestrator.session.forLog

/**
 * Drives a journey between its [Journey] object and the rest of the orchestrator. A journey
 * decides in pure values. Everything with side effects happens once, for every journey alike, so no
 * journey can forget the ACR cap or skip the change log.
 *
 * Each transition passes four phases (docs/04-orchestrierung.md #8, "Die vier Phasen"):
 *
 * | Phase | Who | What |
 * |---|---|---|
 * | read | [JourneyContextFactory] | assembles the read-only [JourneyContext] a journey decides on |
 * | decide | [Journey] | turns (state, event, ctx) into a [Transition] |
 * | act | [JourneyActionExecutor] | executes the [Action] a [Transition.Perform] carries |
 * | route | [JourneyRouting] | derives `next`/[Step] from the resulting state |
 *
 * This class owns what none of the four can own alone: the journey lifecycle, the transition loop,
 * the attempt budget and the cancellation fallout. The channel's Keycloak session is
 * [ChannelSessionLifecycle]'s; what a lost session means for the journeys is decided here.
 */
@Service
// A channel ended because its Keycloak session did must stay ended (ADR-43).
// An exhausted or aborted journey must stay FAILED (I-2).
@Transactional(noRollbackFor = [ChannelSessionEndedException::class, JourneyEndedException::class])
class JourneyService(
    private val journeyRepository: AuthJourneyRepository,
    private val codec: JourneyStateCodec,
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
    // Lifecycle ---------------------------------------------------------------

    /**
     * Starts a journey and produces its first offer. [start] names the journey and the state it
     * begins in: the concrete wish (a step-up target, a method to remove) or, for a journey without
     * input, its [JourneyWithoutInput.firstState]. [restoredEvidence], what the same Keycloak session
     * proved before (ADR-59), is applied as a logged entry transition before [JourneyEvent.Started],
     * so the journey already sees it when it decides what to offer (docs/04-orchestrierung.md #8,
     * "Übernommene Nachweise als erster Übergang").
     * Only on a [LiveChannel]: an ended channel never gets a journey again (docs/invarianten.md I-1).
     */
    fun start(
        channel: LiveChannel,
        start: JourneyStart<*>,
        restoredEvidence: List<MethodEvidence>? = null
    ): Step {
        keepSessionAlive(channel)
        return startJourney(channel.session, start, parentJourneyId = null, restoredEvidence = restoredEvidence)
    }

    private fun startJourney(
        channel: ChannelSession,
        start: JourneyStart<*>,
        parentJourneyId: JourneyId?,
        restoredEvidence: List<MethodEvidence>?,
        optionalForParent: Boolean = false,
    ): Step {
        // One running journey per channel (docs/invarianten.md I-3). A new top-level journey
        // replaces the whole running chain. A sub-journey is the legitimate exception: its parent
        // is suspended, not started.
        if (parentJourneyId == null) {
            findActive(channel.id)?.let { cancelChain(it.entity, channel) }
        }
        val now = clock.instant()
        val journey = AuthJourney(channel.channelSessionId, start.journey, now.plus(JOURNEY_TTL), now)
        journey.accountId = channel.accountId
        journey.parentJourneyId = parentJourneyId
        journey.optionalForParent = optionalForParent
        journey.featureFlags = contextFactory.activeFeatureFlags()
        codec.write(journey, start.seed)
        journeyRepository.save(journey) // journeyId exists from here on, for the entry transition's log line.

        restoredEvidence?.let { methods ->
            // The entry transition: decided by no journey, but logged like every transition.
            journeyTraceService.record(channel.forLog(), journey.forLog(), "Entry", detail = mapOf("methods" to journeyTraceDetails.methodEvidenceDetail(methods)))
            journeyRecorder.restoreEvidence(channel, methods)
        }

        return advance(journey, channel, JourneyEvent.Started)
    }

    /**
     * Starts or restarts the journey this channel was entered with. It lives on the channel
     * so neither resume nor cancel has to guess it from leftover state. [restoredEvidence] as in [start].
     */
    fun startEntryJourney(channel: LiveChannel, restoredEvidence: List<MethodEvidence>? = null): Step = startEntryJourney(channel.session, restoredEvidence)

    private fun startEntryJourney(channel: ChannelSession, restoredEvidence: List<MethodEvidence>?): Step {
        // REGISTERING is never stored: it shows an account still being set up (ChannelState.shownWith).
        if (channel.state != ChannelState.AUTHENTICATED) {
            channel.state = ChannelState.ANONYMOUS
            sessionManagementService.updateChannelSession(channel)
        }
        return startJourney(channel, entryStart(channel), parentJourneyId = null, restoredEvidence = restoredEvidence)
    }

    /** The running journey of this channel, if any. A suspended parent is not it. */
    fun findActive(channelSessionId: ChannelSessionId): RunningJourney? =
        journeyRepository
            .findFirstByChannelSessionIdAndLifecycleOrderByCreatedAtDesc(channelSessionId, JourneyLifecycle.STARTED)
            ?.let { RunningJourney.of(it, clock.instant()) }

    /**
     * The journey [journeyId] names, but only while it runs. A tool session outlives its journey's
     * end (logout, cancel, a finished sub-journey) - through this lookup it can no longer reach it.
     */
    fun findRunning(journeyId: JourneyId): RunningJourney? =
        journeyRepository.findByJourneyId(journeyId)?.let { RunningJourney.of(it, clock.instant()) }

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
            val parent = current.parentJourneyId?.let { journeyRepository.findByJourneyId(it) } ?: break
            chain.add(parent)
            current = parent
        }
        val availableTools = routing.availableToolsOf(channel)
        // Only the innermost journey offers a step. A suspended parent waits on its sub-journey.
        val innermostNote = DemoStepReason.explain(codec.read(innermost), availableTools)
        return chain.reversed().map {
            JourneyDebugStep(
                journeyId = it.journeyId.toString(),
                intent = it.requireKind().name,
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
    fun endSession(channel: LiveChannel, finalState: ChannelState, endedAtKeycloak: Boolean = false, recordSignOut: Boolean = true) =
        sessionLifecycle.end(channel.session, finalState, endedAtKeycloak, recordSignOut)

    /** Cancels [running] and every ancestor suspended for it, so nothing of the chain is left waiting. */
    private fun cancelChain(running: AuthJourney, channel: ChannelSession) {
        markCancelledChain(running, channel)
        fallBack(channel)
    }

    private fun markCancelledChain(running: AuthJourney, channel: ChannelSession) {
        markCancelled(running, channel)
        var parentId = running.parentJourneyId
        while (parentId != null) {
            val parent = journeyRepository.findByJourneyId(parentId) ?: break
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
     * `next` as a pure function of the state (docs/04-orchestrierung.md #6). [JourneyState.activatable]
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
    fun activate(running: RunningJourney, live: LiveChannel, tool: Tool, toolSessionId: ToolSessionId) {
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
        journeyTraceService.record(channel.forLog(), journey.forLog(), "TOOL_ACTIVATED", journeyState = state::class.simpleName,
            detail = mapOf("toolId" to tool.toolId, "tool" to channel.declaredVersionOf(tool.toolId.value)))
    }

    /** See [JourneyActionExecutor.matchesAttestedIdentity]. */
    fun matchesAttestedIdentity(journey: RunningJourney, channel: ChannelSession, personId: PartnerNumber): Boolean =
        actionExecutor.matchesAttestedIdentity(journey.entity, channel, personId)

    /**
     * `active` stays in the stored state of an ended journey. [RunningJourney] ensures such a
     * journey is never asked, so no lifecycle check is needed here.
     */
    fun isCurrent(journey: RunningJourney, toolId: ToolId, toolSessionId: ToolSessionId): Boolean =
        codec.read(journey.entity).active?.let { it.toolId == toolId && it.toolSessionId == toolSessionId } ?: false

    fun applyOutcome(
        running: RunningJourney,
        channel: LiveChannel,
        tool: Tool,
        outcome: ToolOutcome
    ): Step {
        keepSessionAlive(channel)
        return applyOutcome(running.entity, channel.session, tool, outcome)
    }

    private fun applyOutcome(
        journey: AuthJourney,
        channel: ChannelSession,
        tool: Tool,
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

        is ToolOutcome.Completed -> complete(journey, channel, tool, outcome)
    }

    /**
     * A completed tool books what its state says ([JourneyState.booking]); no journey is asked.
     * The journey hears only the [JourneyEvent.ActionCompleted] that follows.
     */
    private fun complete(journey: AuthJourney, channel: ChannelSession, tool: Tool, outcome: ToolOutcome.Completed): Step {
        val state = codec.read(journey)
        val transition = Transition.Perform(state.booking(tool, outcome), resumeState = state)
        recordStep(journey, channel, "Completed", journeyTraceDetails.completedDetail(tool, outcome, channel), state, transition)
        return applyTransition(journey, channel, transition)
    }

    /**
     * "Zurück": the running tool ends without being declined, and the selection page returns with
     * every candidate, the abandoned one included. Whoever goes back wants to choose again. The
     * journey is not asked. Only an [OfferingState] has a selection page; elsewhere this is [abandon].
     */
    fun back(running: RunningJourney, live: LiveChannel, tool: Tool): Step {
        keepSessionAlive(live)
        val journey = running.entity
        val channel = live.session
        val state = codec.read(journey)
        if (state !is OfferingState) return abandon(running, live, tool)
        val cleared = state.withOffer(state.offer.withActive(null))
        codec.write(journey, cleared)
        journeyRepository.save(journey)
        journeyTraceService.record(channel.forLog(), journey.forLog(), "Back",
            journeyState = state::class.simpleName,
            detail = mapOf("toolId" to tool.toolId, "tool" to channel.declaredVersionOf(tool.toolId.value)))
        return routing.selectionFor(cleared, channel)
    }

    /** "Anderes Verfahren": the tool is declined, and the state decides whether anything is left. */
    fun abandon(running: RunningJourney, live: LiveChannel, tool: Tool): Step {
        keepSessionAlive(live)
        val journey = running.entity
        val channel = live.session
        val state = codec.read(journey)
        codec.write(journey, state.withActive(null))
        journeyRepository.save(journey)
        return advance(journey, channel, JourneyEvent.Abandoned(tool))
    }

    /**
     * An answer to what the current [AnswerableState] waits on, instead of a tool run. What it means
     * belongs to the state and its journey, not to this entry point.
     */
    fun answer(running: RunningJourney, live: LiveChannel, answer: Answer): Step {
        keepSessionAlive(live)
        val journey = running.entity
        val channel = live.session
        val state = codec.read(journey)
        if (state !is AnswerableState) {
            throw OrchestratorException.invalidState(Text("Nothing is currently waiting for an answer"))
        }
        return advance(journey, channel, JourneyEvent.Answered(answer))
    }

    // Transitions ----------------------------------------------------------------

    /** [decided]: a transition already fixed by the machine, so the journey is not asked (still traced). */
    private fun advance(journey: AuthJourney, channel: ChannelSession, event: JourneyEvent, decided: Transition? = null): Step {
        val kind = journey.requireKind()
        var state = codec.read(journey)
        var event = event
        val ctx = contextFactory.contextFor(journey, channel)
        var transition: Transition? = decided
        // An offer answers backing out itself (DeclinePolicy); the journey only hears that the
        // offer is exhausted, with everything declined.
        if (event is JourneyEvent.Abandoned && state is OfferingState) {
            val own = state.afterAbandoning(event.tool.toolId, ctx.availableTools)
            if (own != null) transition = Transition.To(own)
            else {
                val narrowed = state.declining(event.tool.toolId)
                state = narrowed
                event = JourneyEvent.OfferExhausted(narrowed.declined)
            }
        }
        transition = transition ?: kind.transitionErased(state, event, ctx)
        recordStep(journey, channel, event::class.simpleName!!, journeyTraceDetails.eventDetail(event, channel), state, transition, ctx)
        return applyTransition(journey, channel, transition)
    }

    /** One trace line per decision: what happened, in which state, and what follows from it. */
    private fun recordStep(
        journey: AuthJourney,
        channel: ChannelSession,
        event: String,
        eventDetail: Map<String, Any?>,
        state: JourneyState,
        transition: Transition,
        ctx: JourneyContext = contextFactory.contextFor(journey, channel),
    ) {
        // The log shows what routing itself derives, so nextFor stays the one routing authority.
        val availableTools = routing.availableToolsOf(channel)
        journeyTraceService.record(channel.forLog(), journey.forLog(), event,
            journeyState = state::class.simpleName,
            // acrFloor is what this step was judged against. resolvedAcr is the combined level
            // of all evidence; a Completed entry's achievedAcr shows only that one tool's
            // ceiling, so two loa1 factors reaching loa2 would otherwise not show.
            detail = eventDetail +
                journeyTraceDetails.transitionDetail(transition, journey, channel, state, availableTools) { target ->
                    routing.nextFor(target, availableTools)
                } +
                state.logDetail +
                mapOf("acrFloor" to ctx.acrFloor, "resolvedAcr" to ctx.policy.resolveAcr(ctx.evidence, ctx.account))
        )
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
            throw JourneyEndedException(transition.reason)
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
        return startJourney(channel, transition.start, parentJourneyId = journey.journeyId, restoredEvidence = null,
            optionalForParent = transition.optional)
    }

    /**
     * Resumes at resumeState against a fresh context, because the action has just changed what the
     * old one showed. An action that raises the level of a login already in place is a step-up
     * (ADR-39), whichever journey ran it: the sign-in log records what happened, not what was asked.
     */
    private fun perform(journey: AuthJourney, channel: ChannelSession, transition: Transition.Perform): Step {
        val acrBefore = if (channel.state?.isLoggedIn == true) contextFactory.currentAcrOf(channel) else null
        val demoNotice = try {
            actionExecutor.perform(journey, channel, transition.action)
        } catch (e: InvitationRefusedException) {
            // The trace says why the journey ended, as for any other Abort.
            journeyTraceService.record(channel.forLog(), journey.forLog(), "InvitationRefused",
                detail = mapOf("decision" to "Abort", "reason" to e.refusal.text))
            return applyTransition(journey, channel, Transition.Abort(e.refusal.text))
        }
        if (acrBefore != null) {
            val acrAfter = contextFactory.currentAcrOf(channel)
            if (acrAfter > acrBefore) journeyRecorder.recordStepUp(channel, acrAfter)
        }
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
        val parent = journey.parentJourneyId?.let { journeyRepository.findByJourneyId(it) }
        if (parent != null && parent.lifecycle == JourneyLifecycle.SUSPENDED) {
            markCancelled(journey, channel)
            parent.lifecycle = JourneyLifecycle.STARTED
            journeyRepository.save(parent)
            // A cancelled precondition cancels the journey that waited on it; only an optional
            // sub-journey's end is left to the waiting journey (RequireSubJourney.optional).
            val decided = Transition.Cancel.takeUnless { journey.optionalForParent }
            return advance(parent, channel, JourneyEvent.SubJourneyCancelled(journey.requireKind()), decided)
        }
        cancelJourney(journey, channel)
        return if (channel.state == ChannelState.AUTHENTICATED) Step(Next.AUTHENTICATED) else startEntryJourney(channel, restoredEvidence = null)
    }

    private fun finish(journey: AuthJourney, channel: ChannelSession): Step {
        journey.consume(clock.instant())
        // Flushed before a suspended parent resumes (ux_journey_running_per_channel).
        journeyRepository.saveAndFlush(journey)
        val parent = journey.parentJourneyId?.let { journeyRepository.findByJourneyId(it) }
            ?.takeIf { it.lifecycle == JourneyLifecycle.SUSPENDED }
        // The sign-in log mirrors the channel: SIGNED_IN is the moment it becomes signed in, so a
        // journey on a channel that already is (CONFIRM_PEER_LOGIN from within a session) adds none.
        val signsIn = parent == null && channel.state?.isLoggedIn != true
        // Before the sign-in log: a login whose session is gone is no sign-in.
        if (parent == null) openLoginSession(channel)
        val acr = contextFactory.currentAcrOf(channel)
        if (signsIn) journeyRecorder.recordSignIn(journey, channel, acr)

        if (parent != null) {
            // The parent picks up exactly where it was parked, so the original wish survives.
            parent.lifecycle = JourneyLifecycle.STARTED
            journeyRepository.save(parent)
            return advance(parent, channel, JourneyEvent.SubJourneyFinished(journey.requireKind(), acr))
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
    private fun chargeAttempt(journey: AuthJourney, channel: ChannelSession, tool: Tool, outcome: ToolOutcome.Failed): Step {
        journey.attemptBudget -= 1
        journeyTraceService.record(channel.forLog(), journey.forLog(), "TOOL_FAILED",
            journeyState = codec.read(journey)::class.simpleName,
            detail = mapOf(
                "toolId" to tool.toolId,
                "tool" to channel.declaredVersionOf(tool.toolId.value),
                "reason" to outcome.reason,
                "attemptedAccountId" to ((outcome as? ToolOutcome.Failed.AccountLookupAuth)?.attempted as? Attempted.Account)?.id,
                "attemptedPersonId" to (outcome as? ToolOutcome.Failed.Identification)?.attemptedPersonId,
                "attemptBudgetLeft" to journey.attemptBudget
            )
        )
        if (journey.attemptBudget <= 0) {
            journey.fail()
            journeyRepository.save(journey)
            throw JourneyEndedException(Text("Retry-Limit erreicht: {reason}", "reason" to outcome.reason))
        }
        journeyRepository.save(journey)
        return Step(nextOf(journey, channel), FailedAttemptStep(outcome.reason))
    }

    // Cancellation fallout -------------------------------------------------------

    /**
     * After an abandoned journey the channel returns to its previous login status
     * ([ChannelState.isLoggedIn]), one rule for every journey. Otherwise a cold step-up could claim
     * AUTHENTICATED without proof (docs/invarianten.md I-4). The account is re-derived from the
     * device link, which survives a journey; an AppTokenSession does not.
     */
    private fun fallBack(channel: ChannelSession) {
        val target = if (channel.state?.isLoggedIn == true) ChannelState.AUTHENTICATED else ChannelState.ANONYMOUS
        channel.state = target
        if (target != ChannelState.AUTHENTICATED) {
            channel.appTokenSessionId = null
            channel.sessionEvidenceId = null
            // The journey's key went with the discarded account, or stays pending for the sweep;
            // the next journey on this channel gets its own (ADR-55).
            channel.journeyKeyId = null
            val abandonedAccountId = channel.accountId
            channel.subject = if (channel.appOpening?.startsWith == StartingAccount.LINKED_TO_DEVICE && channel.bindingKeyRef != null) {
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
    private fun discardIfBeingSetUp(accountId: AccountId?) {
        if (accountId == null || !accountService.isBeingSetUp(accountId)) return
        accountDeletionService.deleteAccount(accountId)
    }

    /** The channel's entry journey; `Journeys` guarantees every one that opens a channel needs no input. */
    private fun entryStart(channel: ChannelSession): JourneyStart<*> {
        val entry = channel.entryJourney
        check(entry is JourneyWithoutInput<*>) { "${entry.name} opens a channel but needs input" }
        return entry.start()
    }

    /**
     * The one place a journey's state type is erased. A journey only gets the state of its own
     * kind; the `intent` column and [JourneyStateCodec.read] guarantee that. A run holds its kind
     * as `Journey<*>`, so the cast lives here once. The `Erased` suffix avoids reading like recursion.
     */
    @Suppress("UNCHECKED_CAST")
    private fun Journey<*>.transitionErased(state: JourneyState, event: JourneyEvent, ctx: JourneyContext): Transition =
        (this as Journey<JourneyState>).transition(state, event, ctx)

    companion object {
        private val JOURNEY_TTL: Duration = Duration.ofMinutes(60)
    }
}
