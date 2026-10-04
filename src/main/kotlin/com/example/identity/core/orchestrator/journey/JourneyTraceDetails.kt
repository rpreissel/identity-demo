package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.tool_api.envelope.Next
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component

/**
 * Fills the JourneyTrace's `detail` column by shaping what a [JourneyEvent], [Transition] or
 * [Action] already determined. It never decides anything. The `next` it logs is passed in by
 * [JourneyService], so the log cannot disagree with routing (docs/04-orchestrierung.md #6).
 */
@Component
class JourneyTraceDetails(
    private val toolRegistry: ToolHandlerRegistry,
    private val accountService: AccountService
) {

    fun methodEvidenceDetail(methods: List<MethodEvidence>): List<Map<String, Any?>> =
        methods.map { mapOf("method" to it.method.value, "loa" to it.loa.value, "factorTypes" to it.factorTypes.map { t -> t.name }, "amrSourceId" to it.amrSourceId) }

    /**
     * Event-specific detail for the trace: the tool involved, in the version [channel] declared
     * (ADR-51), and how the outcome or answer read.
     */
    fun eventDetail(event: JourneyEvent, channel: ChannelSession): Map<String, Any?> = when (event) {
        is JourneyEvent.Completed -> mapOf("toolId" to event.tool.toolId, "tool" to channel.declaredVersionOf(event.tool.toolId.value), "method" to event.tool.method) +
            outcomeDetail(event.tool, event.outcome)
        is JourneyEvent.Abandoned -> mapOf("toolId" to event.tool.toolId, "tool" to channel.declaredVersionOf(event.tool.toolId.value))
        is JourneyEvent.Answered -> mapOf("answer" to event.answer)
        is JourneyEvent.SubJourneyFinished -> mapOf("subIntent" to event.intent.name, "achievedAcr" to event.achievedAcr)
        is JourneyEvent.SubJourneyCancelled -> mapOf("subIntent" to event.intent.name)
        JourneyEvent.Started -> emptyMap()
        JourneyEvent.EvidenceReported -> emptyMap()
        JourneyEvent.ActionCompleted -> emptyMap()
    }

    /** The variant-specific fields of a completed tool run, besides the common ones. */
    private fun outcomeDetail(tool: Tool, outcome: ToolOutcome.Completed): Map<String, Any?> {
        val common = mapOf(
            "outcome" to outcome::class.simpleName,
            "amr" to tool.amrOf(outcome),
            "achievedAcr" to tool.levelOf(outcome),
            "factorTypes" to tool.factorsOf(outcome).map { it.name }
        )
        val specific = when (outcome) {
            is ToolOutcome.Completed.Identified -> mapOf("personId" to outcome.personId)
            is ToolOutcome.Completed.Enrolled -> mapOf("enrollmentRef" to outcome.enrollmentRef.toString())
            is ToolOutcome.Completed.Authenticated -> when (val subject = outcome.subject) {
                is Subject.Account -> mapOf("accountId" to subject.id)
                is Subject.Invitation -> mapOf("invitation" to subject.id)
                null -> mapOf("accountId" to null)
            }
            is ToolOutcome.Completed.Approved -> emptyMap()
            // Attribute types only, never values: a confirmed address does not belong in a debug trace.
            is ToolOutcome.Completed.Attested ->
                mapOf("attested" to outcome.claims.map { it.attributeType.wireName })
        }
        return common + specific
    }

    /**
     * Where the transition leads: target state, sub-intent or action. [availableTools] and [next]
     * come from routing, so the log shows what the client will see.
     */
    fun transitionDetail(
        transition: Transition,
        journey: AuthJourney,
        channel: ChannelSession,
        state: JourneyState,
        availableTools: Set<ToolId>,
        next: (JourneyState) -> Next
    ): Map<String, Any?> = when (transition) {
        is Transition.To -> {
            val candidates = transition.state.activatable(availableTools)
            mapOf(
                "decision" to "To",
                "toState" to transition.state::class.simpleName,
                "authCandidates" to candidates.map { toolId -> toolId to toolRegistry.toolOf(toolId).method }.toMap(),
                "next" to next(transition.state).let { n -> mapOf("type" to n.type, "toolId" to n.toolId, "context" to n.context, "step" to n.step) }
            )
        }
        is Transition.RequireSubJourney -> mapOf(
            "decision" to "RequireSubJourney", "subIntent" to transition.intent.name,
            // Log only: both seed types carry a targetAcr under unrelated interfaces.
            "targetAcr" to when (val seed = transition.seedWith) {
                is StepUpState -> seed.targetAcr
                is ReIdentifyState -> seed.targetAcr
                else -> null
            }
        )
        // [state] is the one before this transition. The RestoreData entry transition can
        // authenticate on the first Started without a `To` entry, so the candidates show here.
        Transition.Authenticated -> mapOf(
            "decision" to "Authenticated",
            "authCandidates" to state.activatable(availableTools)
                .map { toolId -> toolId to toolRegistry.toolOf(toolId).method }.toMap()
        )
        is Transition.Perform -> mapOf("decision" to "Perform", "action" to transition.action::class.simpleName) +
            actionDetail(transition.action, journey, channel)
        Transition.Logout -> mapOf("decision" to "Logout")
        Transition.Cancel -> mapOf("decision" to "Cancel")
        is Transition.Abort -> mapOf("decision" to "Abort", "reason" to transition.reason)
    }

    /**
     * What an [Action] carries worth logging, mainly which method a [Action.RevokeAuthMethod]
     * names. Tool-outcome actions add nothing: the executor derives account and device binding
     * itself, and the journey's state records the result.
     */
    fun actionDetail(action: Action, journey: AuthJourney, channel: ChannelSession): Map<String, Any?> = when (action) {
        is Action.RecordIdentification, is Action.AdoptAttestation -> emptyMap()
        is Action.AdoptCredential -> emptyMap()
        is Action.AcceptProof -> emptyMap()
        is Action.RecordApproval -> emptyMap()
        is Action.ApplyRestoredEvidence -> mapOf("source" to action.source, "methods" to methodEvidenceDetail(action.methods))
        is Action.RevokeAuthMethod -> {
            val accountId = channel.accountId
            val target = accountId?.let { accountService.findAccount(it) }
                ?.authenticationMethods?.firstOrNull { it.id == action.methodInstanceId }
            mapOf("methodInstanceId" to action.methodInstanceId, "method" to target?.method, "label" to target?.label)
        }
        // Names the attribute; the resulting revocations log themselves.
        is Action.RetractAttribute -> mapOf("attribute" to action.attributeType.wireName)
        // These act on the session's account, logged from where the executor reads it.
        is Action.LinkDevice, is Action.DeleteAccount ->
            mapOf("accountId" to channel.accountId)
    }
}
