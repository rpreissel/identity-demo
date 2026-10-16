package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.contract.tool_api.claims.AcrLevel
import kotlin.reflect.KClass

/**
 * A kind of journey: what the user (or the server, for a sub-journey) wants to achieve, where it
 * may start and how it gets there (docs/04-orchestrierung.md #2 and #8). One object per kind; one
 * run of it is an `AuthJourney`.
 *
 * It decides, it never acts: it reads a [JourneyContext] and names an [Action]; every side effect
 * is executed centrally by `JourneyService`. [transition] is the one place it answers "what happens
 * next"; every trigger is a [JourneyEvent].
 *
 * Not a description of what a run turned out to be: whether a run was a registration or a login is
 * an observation about the path taken, not a goal chosen up front.
 */
interface Journey<S : JourneyState> {
    /** Stable name: persisted, traced and accepted on the wire (case-insensitive). */
    val name: String

    /** Where this journey may begin. Checked once for every journey by `Journeys`. */
    val startPoints: List<StartPoint>

    /** The sealed root of this journey's states; `JourneyStateCodec` reads them back under it. */
    val stateRoot: KClass<out S>

    /**
     * The transition function. After a [Transition.Perform], JourneyService executes the [Action],
     * refreshes the [JourneyContext] and calls this again with [JourneyEvent.ActionCompleted], so
     * follow-up logic (e.g. "is the floor satisfied now?") sees the post-action context.
     */
    fun transition(state: S, event: JourneyEvent, ctx: JourneyContext): Transition
}

/**
 * A journey that begins without input from whoever starts it. Every journey that opens a channel is
 * one (`Journeys` checks it), so a cancelled run can restart it; so are those an endpoint starts
 * with nothing to pass on.
 */
interface JourneyWithoutInput<S : JourneyState> : Journey<S> {
    /**
     * Where every run begins. A fixed state: what to offer is decided on the first event
     * ([JourneyEvent.Started]), when taken-over evidence is already in.
     */
    val firstState: S

    /** Starts a run of this journey at [firstState]. */
    fun start(): JourneyStart<S> = JourneyStart(this, firstState)
}

/**
 * Which journey a run is of, and the state it begins in. A journey that needs input offers its own
 * way to build one (e.g. `StepUpJourney.toward(goal)`), so [seed] can only be a state of [journey].
 */
data class JourneyStart<S : JourneyState>(val journey: Journey<S>, val seed: S)

/** One place a journey may begin. A journey lists one or more of them. */
sealed interface StartPoint

/**
 * The App opens a new channel with this journey. [startsWith] and [onSuccess] apply to every
 * journey that later runs on that channel, sub-journeys and journeys started when authenticated
 * included: what happens to the device's link is decided once, when the channel is opened.
 */
data class OpensAppChannel(val startsWith: StartingAccount, val onSuccess: DeviceLink) : StartPoint

/** Keycloak opens a new Web channel with this journey. A Web channel has no device, so nothing to say about linking. */
data object OpensWebChannel : StartPoint

/**
 * Started on an already AUTHENTICATED channel, App or Web, whose subject is an account. A process
 * access (ADR-48) is signed in without one and serves only its process, so it does not count
 * ([startRefusal]).
 */
data object StartableWhenAuthenticated : StartPoint

/** No client starts it; the server reaches it only as another journey's [Transition.RequireSubJourney]. Stands alone. */
data object OnlyAsSubJourney : StartPoint

/** Which account a new App channel starts from, when opened and again after a cancel. */
enum class StartingAccount {
    /** The account this device is linked to (`DeviceAccountLink`), if any. */
    LINKED_TO_DEVICE,

    /** None: the run finds or creates its own account, even on a device that already knows one. */
    NONE,
}

/**
 * What succeeding on an App channel does with the device's link to the account (docs/09-dpop.md #3).
 * Never a silent rebind: a device linked to another account is only moved after an explicit answer
 * (`linksDeviceImplicitly`), because the move revokes that account's device credentials.
 */
enum class DeviceLink {
    /** Linked as a side effect: choosing this journey is consent to be recognized next time. */
    LINK_WITHOUT_ASKING,

    /** Success alone never links; the journey asks first (`DeviceLinkQuestion`). */
    ASK_BEFORE_LINKING,

    /** The run only works with the account the device is already linked to, so the link stays as it is. */
    LEAVE_UNCHANGED,
}

/** Why a journey may not start on an already open channel. */
enum class StartRefusal { NOT_STARTABLE_WHEN_AUTHENTICATED, NOT_LOGGED_IN, NO_ACCOUNT }

/** Whether a new channel of [type] may be opened with this journey. */
fun Journey<*>.opensChannelOn(type: ChannelType): Boolean = when (type) {
    ChannelType.APP -> appChannelOpening != null
    ChannelType.WEB -> OpensWebChannel in startPoints
}

/** How a new App channel opened with this journey behaves; null if the App cannot open one with it. */
val Journey<*>.appChannelOpening: OpensAppChannel?
    get() = startPoints.filterIsInstance<OpensAppChannel>().singleOrNull()

/**
 * Why this journey may not start on an open channel in [state], or null if it may. The one place
 * that decides "only on an AUTHENTICATED channel with an account".
 */
fun Journey<*>.startRefusal(state: ChannelState?, hasAccount: Boolean): StartRefusal? = when {
    StartableWhenAuthenticated !in startPoints -> StartRefusal.NOT_STARTABLE_WHEN_AUTHENTICATED
    state != ChannelState.AUTHENTICATED -> StartRefusal.NOT_LOGGED_IN
    // Each acts on the account itself.
    !hasAccount -> StartRefusal.NO_ACCOUNT
    else -> null
}
