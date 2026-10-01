package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.tool_api.ToolId

/**
 * Into a login on this device as fast as possible, and in a way that works again next time
 * (docs/journeys/fast-access.md). [PreferredAuth] and [AuthChoice] form a fallback chain; once
 * nothing is left, REGISTER runs as a sub-journey, which owns identification and everything it
 * triggers. [AuthChoice] and [Enrolling] are shared with [RegisterState].
 */
sealed interface FastAccessState : JourneyState {

    data object Start : FastAccessState, ToolFreeState {
        override val selectionContext: String get() = "auth"
    }

    /** Linked device with a matching device method: exactly one default suggestion. */
    data class PreferredAuth(val toolId: ToolId, override val active: ToolRef? = null) : FastAccessState {
        override fun withActive(active: ToolRef?) = copy(active = active)
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = setOf(toolId) intersect availableTools
        override val selectionContext: String get() = "auth"
    }
}
