package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.declineTool
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
 * [selfServiceAcrFloor] and to hold a recent proof. The wish stays parked in its state while the
 * step-up runs; re-evaluating that state re-checks the gate and then carries out the wish. A
 * re-proof for freshness authorizes only this one wish, so it is not recorded as `MethodEvidence`.
 */
class ManageAuthMethodsStrategy : IntentStrategy<ManageAuthMethodsState> {

    override val intent = AuthIntent.MANAGE_AUTH_METHODS

    override fun initialState(ctx: JourneyContext): ManageAuthMethodsState = ManageAuthMethodsState.AddRequested

    override fun transition(state: ManageAuthMethodsState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            // After a declined step-up, gate() would re-request it forever, so cancel instead.
            is ManageAuthMethodsState.AddRequested ->
                if (event is JourneyEvent.SubJourneyCancelled) Transition.Cancel
                // Nothing to add needs no fresh proof either.
                else gate(state, ctx) ?: offerEnrollment(ctx).let { offer ->
                    if (offer is Transition.Authenticated) offer else freshness(state, ctx) ?: offer
                }

            is ManageAuthMethodsState.RemoveRequested, is ManageAuthMethodsState.RetractAttributeRequested -> when (event) {
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> gate(state, ctx) ?: freshness(state as ManageAuthMethodsState.Wish, ctx) ?: carryOut(state, ctx)
            }

            is ManageAuthMethodsState.ConfirmationRequired -> when (event) {
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) { Transition.Cancel }
                // Any active factor suffices; straight to the wish, without Action.AcceptProof.
                is JourneyEvent.Completed -> when (event.outcome) {
                    is ToolOutcome.Completed.Authenticated -> carryOut(state.wish, ctx)
                    is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Enrolled, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
                        event.notOffered("MANAGE")
                }
                else -> error("ConfirmationRequired does not understand $event")
            }

            is ManageAuthMethodsState.Enrolling -> when (event) {
                // Backing out means picking a different method; the full choice comes back.
                // Giving up entirely is DELETE .../journey.
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event), resumeState = state)
                // The enrollment just ran - one successful enrollment ends this intent.
                else -> Transition.Authenticated
            }
        }

    /** What [wish] asked for, once gate and freshness are cleared. A removal resumes in its wish, which then finishes. */
    private fun carryOut(wish: ManageAuthMethodsState.Wish, ctx: JourneyContext): Transition = when (wish) {
        ManageAuthMethodsState.AddRequested -> offerEnrollment(ctx)
        is ManageAuthMethodsState.RemoveRequested -> Transition.Perform(Action.RevokeAuthMethod(wish.methodInstanceId), resumeState = wish)
        is ManageAuthMethodsState.RetractAttributeRequested -> Transition.Perform(Action.RetractAttribute(wish.attributeType), resumeState = wish)
    }

    /** Null once the session's latest proof is recent enough; else the re-confirmation, the wish in hand. */
    private fun freshness(wish: ManageAuthMethodsState.Wish, ctx: JourneyContext): Transition? {
        if (ctx.policy.hasFreshProof(ctx.evidence)) return null
        val candidates = CandidateTools.forReconfirmation(ctx.requireAccount(), ctx)
        // Unreachable on an authenticated channel; abort rather than act silently.
        return if (candidates.isEmpty()) Transition.Abort(Text("Kein aktiver Faktor zur erneuten Bestaetigung verfuegbar"))
        else Transition.To(ManageAuthMethodsState.ConfirmationRequired(Offer(candidates), wish))
    }

    /**
     * Voluntary enrollment on an authenticated channel. Binding the known device again is a
     * harmless no-op that keeps a new device credential reachable next time.
     */
    private fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome)
        is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Authenticated, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
            event.notOffered("MANAGE")
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
