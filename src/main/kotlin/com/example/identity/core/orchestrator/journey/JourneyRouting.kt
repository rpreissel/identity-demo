package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.session.channelType
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.journey.state.AnswerableState
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.tool.ToolAvailabilityService
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.envelope.Next
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolVersion
import org.springframework.stereotype.Component

/**
 * One step as the client sees it: `next` plus what the step needs to render. `next` is null only
 * for a decision that ends the channel ([Transition.Logout]). [demo] is a separate field because
 * the demo block is not part of the step's contract (docs/05-api.md, `demo`).
 */
data class Step(
    val next: Next?,
    val stepData: StepData? = null,
    val demo: Map<String, Any?>? = null
)

/**
 * The routing phase of a transition: turns a [JourneyState] into the client's `next` plus the data
 * the step needs. A pure function of state and available tools; it reads no journey and writes
 * nothing. One small class keeps "next is a pure function of the state" checkable
 * (docs/04-orchestrierung.md #4).
 */
@Component
class JourneyRouting(
    private val toolRegistry: ToolHandlerRegistry,
    private val toolAvailabilityService: ToolAvailabilityService
) {
    /**
     * What the client declared it can render, minus what the operator switched off for this
     * channel type. Live, not cached: a disable applies to the next step of a running journey.
     */
    fun availableToolsOf(channel: ChannelSession): Set<ToolId> {
        val disabled = toolAvailabilityService.disabledToolIds(channelTypeOf(channel))
        // The version matters only to the client's calls; what is offered is the tool (ADR-51).
        return channel.availableClientTools.map { ToolVersion.parse(it).toolId }
            .filterTo(mutableSetOf()) { it.value !in disabled }
    }

    /**
     * `next` as a pure function of the state (docs/04-orchestrierung.md #4). [JourneyState.activatable]
     * decides both what may be activated and where the client goes, so the two cannot disagree.
     */
    fun nextFor(state: JourneyState, availableTools: Set<ToolId>): Next {
        state.active?.let { return Next.tool(it.toolId.value, it.step, it.toolSessionId) }
        val activatable = state.activatable(availableTools)
        val single = activatable.singleOrNull()
        return if (single != null && withoutUserStep(single) == null) {
            Next.tool(single.value, toolRegistry.toolOf(single).startStep)
        } else {
            // A single candidate that completes on activation gets the selection page too: started
            // alone it would change the account before the user saw anything. Zero candidates
            // mean an orchestrator page without a choice, or an offer that just became unavailable;
            // the client's next action resolves that.
            Next.orchestrator(state.selectionContext, state.selectionStep)
        }
    }

    /**
     * The complete current step, including selection options and prompts. The cases exclude each
     * other, so [StepData] is a union: a choice, an auto-activated single candidate, or a question.
     */
    fun stepFor(state: JourneyState, channel: ChannelSession): Step {
        val availableTools = availableToolsOf(channel)
        val options = state.activatable(availableTools)
        val withoutUserStep = options.singleOrNull()?.let { withoutUserStep(it) }
        val stepData: StepData? = when {
            // Sorted here, not in the stored offer, which is frozen for the journey: a changed
            // order applies to the next screen of a running journey too.
            state is OfferingState && options.size > 1 -> selectMethodStep(state, channel, options)
            // The only candidate completes on activation: offered as a choice of one, with a
            // description of why.
            state is OfferingState && withoutUserStep != null -> selectMethodStep(state, channel, options)
            // Single candidate, auto-activated: the description travels as a message, so the tool
            // form can explain why this step is required.
            state is OfferingState && options.size == 1 ->
                state.selectionDescription?.let { MessageStep(it) }
            state is AnswerableState -> ConfirmStep(state.question.toPrompt())
            else -> null
        }
        return Step(nextFor(state, availableTools), stepData)
    }

    /**
     * The selection page of [state] even with one candidate left, for "Zurück": whoever goes back
     * wants to choose, not land in the same tool again as [nextFor] would.
     */
    fun selectionFor(state: OfferingState, channel: ChannelSession): Step {
        val options = state.activatable(availableToolsOf(channel))
        return Step(Next.orchestrator(state.selectionContext, state.selectionStep), selectMethodStep(state, channel, options))
    }

    private fun selectMethodStep(state: OfferingState, channel: ChannelSession, options: Set<ToolId>): SelectMethodStep {
        val withoutUserStep = options.singleOrNull()?.let { withoutUserStep(it) }
        return SelectMethodStep(
            options = toolAvailabilityService.ordered(channelTypeOf(channel), options).map { it.value },
            title = state.selectionTitle,
            description = withoutUserStep?.let { Text("Nur dieses Verfahren steht hier noch zur Wahl. {grund}", "grund" to it) }
                ?: state.selectionDescription
        )
    }

    private fun withoutUserStep(toolId: ToolId): Text? = toolRegistry.toolOf(toolId).withoutUserStep

    private fun channelTypeOf(channel: ChannelSession): ChannelType =
        channel.channelType
}
