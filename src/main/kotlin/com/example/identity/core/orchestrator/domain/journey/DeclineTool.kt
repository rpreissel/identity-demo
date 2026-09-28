package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.contract.tool_api.ToolId

/**
 * The answer every offering state gives to "the user backed out of this tool": narrow the offer,
 * and if nothing offerable is left ([OfferingState.exhausted], which also respects availability),
 * hand over to the caller's fallback. [whenExhausted] is per intent (plain `Cancel`, a
 * re-identification offer, an enrollment cascade) and gets the full [declined] set.
 */
inline fun declineTool(
    state: OfferingState,
    tool: ToolId,
    ctx: JourneyContext,
    whenExhausted: (declined: Set<ToolId>) -> Transition
): Transition {
    val narrowed = state.declining(tool)
    return if (narrowed.exhausted(ctx.availableTools)) whenExhausted(narrowed.declined)
    else Transition.To(narrowed)
}

/**
 * The two answers an [com.example.identity.core.orchestrator.domain.journey.state.AnswerableState] prompt
 * accepts, as the client sends them ([JourneyEvent.Answered]). A wire contract, so defined once.
 */
const val ANSWER_ACCEPT = "accept"
const val ANSWER_DECLINE = "decline"
