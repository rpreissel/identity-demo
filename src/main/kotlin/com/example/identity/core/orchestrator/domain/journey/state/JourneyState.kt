package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.ToolId

/**
 * The position on the path, together with the attributes that hold at this position
 * (docs/04-orchestrierung.md #1), e.g. which methods were offered and which were declined. Every
 * intent owns its own sealed set, so a forgotten position is a compile error in a `when`.
 * [activatable] answers both "which tool may the client activate now?" and "where next?"
 * ([JourneyService.nextOf]).
 */
sealed interface JourneyState {
    /**
     * Empty for states that wait on something other than a tool. [availableTools] is applied live
     * here, so a tool switched off after this state was written disappears from every caller without
     * recomputing the state.
     */
    fun activatable(availableTools: Set<ToolId>): Set<ToolId>

    /** The tool that is actually running, once one has been activated. */
    val active: ToolRef?

    /** `next.context` of the orchestrator-owned page this state maps to. */
    val selectionContext: String

    /** `next.step` of that page. */
    val selectionStep: String
        get() = "selectMethod"

    /**
     * The same state with a different running tool. A state that cannot host a tool (a
     * confirmation, a parked wish) ignores this; the compiler forces every new state to choose.
     */
    fun withActive(active: ToolRef?): JourneyState

    /**
     * State-owned flags for the journey trace (e.g. [WebSelectMethodState.accountAlreadyKnown]), so
     * `JourneyService` can log them without downcasting.
     */
    val logDetail: Map<String, Any?> get() = emptyMap()
}

/**
 * Which ToolSession may act as [toolId] right now. The id matters: a superseded ToolSession (e.g.
 * from a duplicate activation) would otherwise pass a toolId-only check and fail deep inside the
 * module instead of with a clean 409 at the boundary.
 */
data class ToolRef(val toolId: ToolId, val toolSessionId: ToolSessionId, val step: String)

/**
 * What a state currently offers: the candidates, what was already declined, and which tool is
 * running. The rules over these fields live here once; a state only holds an [Offer] and puts a
 * new one back ([OfferingState.withOffer]).
 */
data class Offer(
    val offered: List<ToolId>,
    val declined: Set<ToolId> = emptySet(),
    val active: ToolRef? = null
) {
    fun withActive(active: ToolRef?): Offer = copy(active = active)

    /**
     * [toolId] declined: recorded, and the running tool stopped, so an abandoned tool is no longer
     * addressable after the journey moved past it.
     */
    fun declining(toolId: ToolId): Offer = copy(declined = declined + toolId, active = null)

    fun activatable(availableTools: Set<ToolId>): Set<ToolId> = (offered.toSet() - declined) intersect availableTools
}

/** Shared shape of every state that offers a set of tools and remembers what was declined. */
sealed interface OfferingState : JourneyState {
    val offer: Offer

    /** This state holding [offer] instead. */
    fun withOffer(offer: Offer): OfferingState

    // Read-through, so every caller keeps saying `state.offered` rather than `state.offer.offered`.
    val offered: List<ToolId> get() = offer.offered
    val declined: Set<ToolId> get() = offer.declined
    override val active: ToolRef? get() = offer.active

    /**
     * Backend-authored heading for the selection screen, so it can change without an app release
     * (as [Question]). Per state, because `selectionContext` only names the screen shared by every
     * intent offering the same kind of candidate, not what the user is asked here.
     */
    val selectionTitle: Text
    val selectionDescription: Text? get() = null

    override fun withActive(active: ToolRef?): JourneyState = withOffer(offer.withActive(active))

    override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = offer.activatable(availableTools)

    /**
     * True once every offer has been declined or nothing left is available. Availability can empty
     * even a mandatory state's offer, so every caller falls back through the same chain as a decline.
     */
    fun exhausted(availableTools: Set<ToolId>): Boolean = activatable(availableTools).isEmpty()

    /** See [Offer.declining]. */
    fun declining(toolId: ToolId): OfferingState = withOffer(offer.declining(toolId))
}

/**
 * A state that runs no tool: a decision point, a parked wish or a yes/no prompt. Nothing to
 * activate, and nothing to remember about a running tool.
 */
sealed interface ToolFreeState : JourneyState {
    override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
    override val active: ToolRef? get() = null
    override fun withActive(active: ToolRef?): JourneyState = this
}

/**
 * A state that pauses for an explicit accept/decline answer instead of a tool run
 * (`JourneyService.answer`). Not sealed, so JourneyService recognizes a waiting yes/no state
 * without importing any intent's states; a new yes/no action needs no change there.
 */
interface AnswerableState : ToolFreeState {
    /** What the client renders while waiting. */
    val question: Question

    /**
     * One shared address for every [AnswerableState]: they all render through the same generic
     * screen (`stepData.prompt` decides the content) and use the same endpoint.
     */
    override val selectionContext: String get() = "prompt"
    override val selectionStep: String get() = "confirm"
}
