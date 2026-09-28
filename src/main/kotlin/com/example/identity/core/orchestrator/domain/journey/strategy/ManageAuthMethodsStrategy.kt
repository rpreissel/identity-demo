package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.selfServiceAcrFloor
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.journey.state.StepUpState
import com.example.identity.contract.tool_api.ToolOutcome

/**
 * Add or remove authentication methods on an already authenticated channel
 * (docs/journeys/manage-auth-methods.md). Every operation first requires the session to clear
 * [selfServiceAcrFloor]. The wish stays parked in its state while the step-up runs; re-evaluating
 * that state re-checks the gate and then carries out the wish.
 */
class ManageAuthMethodsStrategy : IntentStrategy<ManageAuthMethodsState> {

    override val intent = AuthIntent.MANAGE_AUTH_METHODS

    override fun initialState(ctx: JourneyContext): ManageAuthMethodsState = ManageAuthMethodsState.AddRequested

    override fun transition(state: ManageAuthMethodsState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            // After a declined step-up, gate() would re-request it forever, so cancel instead.
            is ManageAuthMethodsState.AddRequested ->
                if (event is JourneyEvent.SubJourneyCancelled) Transition.Cancel else gate(state, ctx) ?: offerEnrollment(ctx)

            is ManageAuthMethodsState.RemoveRequested -> when (event) {
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                // The removal just ran.
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> gate(state, ctx) ?: Transition.Perform(Action.RevokeAuthMethod(state.methodInstanceId), resumeState = state)
            }

            // Same shape as RemoveRequested: gate first, then act, then finish.
            is ManageAuthMethodsState.RetractAttributeRequested -> when (event) {
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> gate(state, ctx) ?: Transition.Perform(Action.RetractAttribute(state.attributeType), resumeState = state)
            }

            is ManageAuthMethodsState.Enrolling -> when (event) {
                // Backing out means picking a different method; the full choice comes back.
                // Giving up entirely is DELETE .../journey.
                is JourneyEvent.Abandoned -> Transition.To(state.withActive(null))
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                // The enrollment just ran - one successful enrollment ends this intent.
                else -> Transition.Authenticated
            }
        }

    /**
     * Voluntary enrollment on an authenticated channel. Binding the known device again is a
     * harmless no-op that keeps a new device credential reachable next time.
     */
    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome)
        is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Authenticated, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
            error("${event.tool.toolId} is not offered by MANAGE")
    }

    /** Null once the session already carries [selfServiceAcrFloor] and the caller may proceed. */
    private fun gate(requested: ManageAuthMethodsState, ctx: JourneyContext): Transition? {
        val account = ctx.requireAccount()
        val requiredAcr = selfServiceAcrFloor(account)
        if (ctx.policy.isSatisfied(ctx.evidence, requiredAcr, account)) return null
        return Transition.RequireSubJourney(
            AuthIntent.STEP_UP,
            seedWith = StepUpState.forSubJourney(requiredAcr, ctx.currentAcr),
            resumeWith = requested
        )
    }

    private fun offerEnrollment(ctx: JourneyContext): Transition {
        val candidates = CandidateTools.forEnrollment(ctx.requireAccount(), ctx.acrFloor, ctx)
        return if (candidates.isEmpty()) {
            // Nothing left to add is not an error (docs/07-betrieb.md #1).
            Transition.Authenticated
        } else {
            Transition.To(ManageAuthMethodsState.Enrolling(Offer(candidates)))
        }
    }
}
