package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState

/** What just happened to the journey. */
sealed interface JourneyEvent {
    /** The journey was just created and has to produce its first offer. */
    data object Started : JourneyEvent

    /**
     * The offer is used up: every tool was declined ("Anderes Verfahren"), or an optional step was
     * backed out of. Backing out of one tool is answered by the offer itself
     * ([com.example.identity.core.orchestrator.domain.journey.state.DeclinePolicy]); only a
     * `FALLBACK` or `OPTIONAL` state sees this, with the full [declined] set, while a mandatory
     * offer simply comes back without any event reaching the journey.
     */
    data class OfferExhausted(val declined: Set<ToolId>) : JourneyEvent

    /**
     * A [Transition.Perform]'s [Action] finished; the [JourneyContext] is fresh. Every state named
     * as [Transition.Perform.resumeState] needs an arm for this event: it is the only one it sees next.
     */
    data object ActionCompleted : JourneyEvent

    /**
     * A sub-journey finished. It is always the one the resumed state requested; [journey] is there
     * for the journey trace. [SubJourneyCancelled] is a separate type, not a flag: a resumed
     * state that re-derived its next step from evidence alone would re-request the sub-journey it
     * was just declined, forever. The compiler forces both arms.
     */
    data class SubJourneyFinished(val journey: Journey<*>, val achievedAcr: AcrLevel) : JourneyEvent

    /**
     * The sub-journey was abandoned without achieving anything, so there is no acr to report.
     * [journey] names which one, as in [SubJourneyFinished]. A journey only sees it for a
     * sub-journey it requested as [Transition.RequireSubJourney.optional]; any other cancels the
     * waiting journey in `JourneyService`.
     */
    data class SubJourneyCancelled(val journey: Journey<*>) : JourneyEvent

    /** An explicit answer to what an [AnswerableState] is waiting on (`JourneyService.answer`). */
    data class Answered(val answer: Answer) : JourneyEvent
}

/**
 * The arm for every event [state] never receives. Each state names the events it does receive, so
 * an event it does not expect is a broken state machine, never a silent success.
 */
fun JourneyEvent.notExpectedIn(state: JourneyState): Nothing =
    error("${state::class.simpleName} does not expect $this")
