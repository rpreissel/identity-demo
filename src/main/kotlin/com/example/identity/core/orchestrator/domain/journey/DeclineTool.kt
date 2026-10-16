package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.ToolId
import com.example.identity.core.orchestrator.domain.journey.state.DeclinePolicy
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState

/**
 * What backing out of [tool] means for this offer, by its [DeclinePolicy]: the next state when the
 * state decides on its own, or `null` when the journey has to, because the offer is exhausted
 * ([OfferingState.exhausted], which also respects availability) or optional. `JourneyService` then
 * sends [JourneyEvent.OfferExhausted] to [narrowedBy], so the journey sees everything declined.
 */
fun OfferingState.afterAbandoning(tool: ToolId, availableTools: Set<ToolId>): JourneyState? = when (declinePolicy) {
    DeclinePolicy.MANDATORY -> withActive(null)
    DeclinePolicy.FALLBACK -> declining(tool).takeUnless { it.exhausted(availableTools) }
    DeclinePolicy.OPTIONAL -> null
}

/** This offer with [tool] declined, for the journey to resume from once [afterAbandoning] returned `null`. */
fun OfferingState.narrowedBy(tool: ToolId): OfferingState = declining(tool)
