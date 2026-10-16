package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.core.orchestrator.domain.journey.Action

/**
 * The position on the path, together with the attributes that hold at this position
 * (docs/04-orchestrierung.md #3), e.g. which methods were offered and which were declined. Every
 * journey owns its own sealed set, so a forgotten position is a compile error in a `when`.
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

    /**
     * What a tool completed in this state books: usually what its outcome stands for ([Action.of]).
     * `JourneyService` performs it before the journey hears anything, so a journey only ever sees
     * [com.example.identity.core.orchestrator.domain.journey.JourneyEvent.ActionCompleted].
     */
    fun booking(tool: Tool, outcome: ToolOutcome.Completed): Action = Action.of(tool, outcome)

    /**
     * The same state with a different running tool. A state that cannot host a tool (a
     * confirmation, a parked wish) ignores this; the compiler forces every new state to choose.
     */
    fun withActive(active: ToolRef?): JourneyState
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

/**
 * The kind of an offer, read when the user backs out of a tool (docs/04-orchestrierung.md, "Zwei
 * Sorten von Übergang"). Declared on the state, not decided by the journey, so the kind is visible
 * where the offer is; [OfferingState.afterAbandoning] answers backing out from it.
 */
enum class DeclinePolicy {
    /** The obligation stands: the full offer comes back, the declined tool included. */
    MANDATORY,

    /** Declining narrows the offer; once nothing is left, the journey names the next, costlier way. */
    FALLBACK,

    /** The whole step may be skipped: the first backing out already hands over to the journey. */
    OPTIONAL,
}

/** Shared shape of every state that offers a set of tools and remembers what was declined. */
sealed interface OfferingState : JourneyState {
    val offer: Offer

    /** What backing out of a tool means here; see [DeclinePolicy]. */
    val declinePolicy: DeclinePolicy

    /** This state holding [offer] instead. */
    fun withOffer(offer: Offer): OfferingState

    // Read-through, so every caller keeps saying `state.offered` rather than `state.offer.offered`.
    val offered: List<ToolId> get() = offer.offered
    val declined: Set<ToolId> get() = offer.declined
    override val active: ToolRef? get() = offer.active

    /** The selection page this offer is shown on. */
    val screen: Screen

    /**
     * Backend-authored heading for the selection screen, so it can change without an app release
     * (as [Question]). Per state, because [screen] only names the page shared by every journey
     * offering the same kind of candidate, not what the user is asked here.
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

    /**
     * What backing out of [tool] means for this offer, by its [declinePolicy]: the next state when
     * the state decides on its own, or `null` when the journey has to, because the offer is
     * exhausted ([exhausted], which also respects availability) or optional. `JourneyService` then
     * sends `JourneyEvent.OfferExhausted` with this offer [declining] the tool, so the journey sees
     * everything declined.
     */
    fun afterAbandoning(tool: ToolId, availableTools: Set<ToolId>): JourneyState? = when (declinePolicy) {
        DeclinePolicy.MANDATORY -> withActive(null)
        DeclinePolicy.FALLBACK -> declining(tool).takeUnless { it.exhausted(availableTools) }
        DeclinePolicy.OPTIONAL -> null
    }
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
 * without importing any journey's states; a new yes/no action needs no change there.
 */
interface AnswerableState : ToolFreeState {
    /** What the client renders while waiting. */
    val question: Question

    /**
     * One shared address for every [AnswerableState]: they all render through the same generic
     * screen (`stepData.prompt` decides the content) and use the same endpoint.
     */
    val screen: Screen get() = Screen.CONFIRM
}

/**
 * The orchestrator-owned pages a client renders, as `next.context`/`next.step` on the wire
 * (docs/05-api.md). Only a state that is shown names one: an offer its selection page, a question
 * the prompt. A decision point or a parked wish is left again in the step that reads it.
 */
enum class Screen(val context: String, val step: String) {
    /** Choose a way to prove: an auth tool of the account, or one that finds the account. */
    SELECT_AUTH_METHOD("auth", "selectMethod"),

    /** Choose how to identify (REGISTER). */
    SELECT_IDENTIFICATION_METHOD("registration", "selectIdentificationMethod"),

    /** Choose the correlation step that binds the account to a person (REGISTER). */
    ASSIGN_PERSON("registration", "selectMethod"),

    /** Choose a method to set up. */
    SELECT_ENROLLMENT("enrollment", "selectMethod"),

    /** A yes/no question; `stepData.prompt` carries the wording. */
    CONFIRM("prompt", "confirm"),
}
