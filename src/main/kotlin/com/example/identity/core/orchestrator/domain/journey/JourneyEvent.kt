package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.core.orchestrator.domain.AuthIntent

/** What just happened to the journey. */
sealed interface JourneyEvent {
    /** The journey was just created and has to produce its first offer. */
    data object Started : JourneyEvent

    /**
     * Evidence was reported directly, outside any tool outcome, e.g. by Keycloak's native
     * authenticators (docs/05-api.md Abschnitt 3). The channel's
     * [com.example.identity.core.orchestrator.session.SessionEvidenceRecord] is already updated, so a strategy only
     * re-checks `ctx.policy.isSatisfied(...)` as after any other proof.
     */
    data object EvidenceReported : JourneyEvent

    /** A tool finished successfully; [outcome] is what a strategy turns into an [Action]. */
    data class Completed(val tool: Tool, val outcome: ToolOutcome.Completed) : JourneyEvent

    /** "Back"/"Switch": the user abandoned an activated tool without finishing it. */
    data class Abandoned(val tool: Tool) : JourneyEvent

    /**
     * A [Transition.Perform]'s [Action] finished; the [JourneyContext] is fresh. Every state named
     * as [Transition.Perform.resumeState] needs an arm for this event: it is the only one it sees next.
     */
    data object ActionCompleted : JourneyEvent

    /**
     * A sub-journey finished. [intent] names which one, so a parent never mistakes a second
     * sub-journey for the first. [SubJourneyCancelled] is a separate type, not a flag: a resumed
     * state that re-derived its next step from evidence alone would re-request the sub-journey it
     * was just declined, forever. The compiler forces both arms.
     */
    data class SubJourneyFinished(val intent: AuthIntent, val achievedAcr: AcrLevel?) : JourneyEvent

    /**
     * The sub-journey was abandoned without achieving anything, so there is no acr to report.
     * [intent] names which one, as in [SubJourneyFinished].
     */
    data class SubJourneyCancelled(val intent: AuthIntent) : JourneyEvent

    /**
     * An explicit answer to what an [AnswerableState] is waiting on (`JourneyService.answer`). A
     * string, not a boolean: the owning strategy decides which values are valid.
     */
    data class Answered(val answer: String) : JourneyEvent
}
