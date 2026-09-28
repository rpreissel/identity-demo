package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.AuthIntent

/**
 * What should happen next. Not a `Next`: the skip-if-single-candidate rule and the routing live
 * once in `JourneyService`. There is no "offer these tools" variant: a state carries its offer
 * ([JourneyState.activatable]), so [To] that state is the offer.
 */
sealed interface Transition {
    /** Move the journey to [state]; what it now offers is read straight off that state. */
    data class To(val state: JourneyState) : Transition

    /**
     * Run [intent] first, seeded at [seedWith] (e.g. `StepUpState.forSubJourney(...)`), then resume
     * this journey at [resumeWith] once it finishes.
     */
    data class RequireSubJourney(
        val intent: AuthIntent,
        val seedWith: JourneyState,
        val resumeWith: JourneyState
    ) : Transition {
        init {
            // [resumeWith] is reactivated only after the sub-journey ran, with new evidence and
            // possibly other methods. A frozen candidate list would be re-offered as current, so
            // resume at a state that recomputes, never at an offer.
            check(resumeWith !is OfferingState) {
                "resumeWith must not be an OfferingState (${resumeWith::class.simpleName}): a sub-journey's " +
                    "whole point is that the situation changed, so the offer has to be recomputed on return"
            }
        }
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
     * Confirmed logout: ends the channel for good. The journey is consumed, authContext discarded,
     * the channel becomes LOGGED_OUT. Also the resume target after [Action.DeleteAccount].
     */
    data object Logout : Transition

    /** No way forward at all. Ends the journey with 410, never for a mere "no candidates left". */
    data class Abort(val reason: Text) : Transition
}
