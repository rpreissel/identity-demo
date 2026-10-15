package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.Tool
import com.example.identity.core.orchestrator.domain.AuthIntent

/** What just happened to the journey. */
sealed interface JourneyEvent {
    /** The journey was just created and has to produce its first offer. */
    data object Started : JourneyEvent

    /**
     * "Back"/"Switch": the user abandoned an activated tool without finishing it. An offering state
     * answers this itself by its [com.example.identity.core.orchestrator.domain.journey.state.DeclinePolicy];
     * a strategy sees it only for a state without an offer (e.g. `FastAccessState.PreferredAuth`).
     */
    data class Abandoned(val tool: Tool) : JourneyEvent

    /**
     * The offer is used up: every tool was declined, or an optional step was backed out of. Only a
     * `FALLBACK` or `OPTIONAL` state sees it, with the full [declined] set; a mandatory offer simply
     * comes back without any event reaching the strategy.
     */
    data class OfferExhausted(val declined: Set<ToolId>) : JourneyEvent

    /**
     * A [Transition.Perform]'s [Action] finished; the [JourneyContext] is fresh. Every state named
     * as [Transition.Perform.resumeState] needs an arm for this event: it is the only one it sees next.
     */
    data object ActionCompleted : JourneyEvent

    /**
     * A sub-journey finished. It is always the one the resumed state requested; [intent] is there
     * for the journey trace. [SubJourneyCancelled] is a separate type, not a flag: a resumed
     * state that re-derived its next step from evidence alone would re-request the sub-journey it
     * was just declined, forever. The compiler forces both arms.
     */
    data class SubJourneyFinished(val intent: AuthIntent, val achievedAcr: AcrLevel?) : JourneyEvent

    /**
     * The sub-journey was abandoned without achieving anything, so there is no acr to report.
     * [intent] names which one, as in [SubJourneyFinished]. A strategy only sees it for a
     * sub-journey it requested as [Transition.RequireSubJourney.optional]; any other cancels the
     * waiting journey in `JourneyService`.
     */
    data class SubJourneyCancelled(val intent: AuthIntent, val optional: Boolean) : JourneyEvent

    /** An explicit answer to what an [AnswerableState] is waiting on (`JourneyService.answer`). */
    data class Answered(val answer: Answer) : JourneyEvent
}
