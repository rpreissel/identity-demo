package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.ToolFreeState
import com.example.identity.core.orchestrator.domain.ChannelState

/**
 * What should happen next. Not a `Next`: the skip-if-single-candidate rule and the routing live
 * once in `JourneyService`. There is no "offer these tools" variant: a state carries its offer
 * ([JourneyState.activatable]), so [To] that state is the offer.
 */
sealed interface Transition {
    /** Move the journey to [state]; what it now offers is read straight off that state. */
    data class To(val state: JourneyState) : Transition

    /**
     * Run the journey [start] names first (e.g. `StepUpJourney.toward(goal)`), then resume this
     * journey at [resumeWith] once it finishes. [resumeWith] is a [ToolFreeState]: it is reactivated
     * only after the sub-journey ran, with new evidence and possibly other methods, so it must
     * recompute what to offer instead of re-offering a frozen candidate list.
     *
     * [optional] says what a cancelled sub-journey means. Not optional (the usual case): it was a
     * precondition, so this journey is cancelled with it, without being asked. Optional: this
     * journey resumes at [resumeWith] with [JourneyEvent.SubJourneyCancelled] and decides itself.
     */
    data class RequireSubJourney(
        val start: JourneyStart<*>,
        val resumeWith: ToolFreeState,
        val optional: Boolean = false,
    ) : Transition {
        val journey: Journey<*> get() = start.journey
    }

    /** Goal reached: consume the journey, the channel becomes AUTHENTICATED. */
    data object Authenticated : Transition

    /**
     * The user gave up (abandoned the last thing this journey could offer). Unlike [Abort] nothing
     * went wrong: the channel returns to its login status before the journey
     * ([ChannelState.isLoggedIn]) instead of a 410.
     */
    data object Cancel : Transition

    /** Run [action], then resume the journey at [resumeState] with [JourneyEvent.ActionCompleted]. */
    data class Perform(val action: Action, val resumeState: JourneyState) : Transition

    /**
     * Confirmed logout: ends the channel for good. The journey is consumed, appTokenSession discarded,
     * the channel becomes LOGGED_OUT. Also the resume target after [Action.DeleteAccount].
     */
    data object Logout : Transition

    /** No way forward at all. Ends the journey with 410, never for a mere "no candidates left". */
    data class Abort(val reason: Text) : Transition
}
