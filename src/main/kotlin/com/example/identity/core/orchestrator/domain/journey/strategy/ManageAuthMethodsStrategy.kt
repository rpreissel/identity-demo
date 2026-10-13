package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
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
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.account.AuthMethodView

/**
 * Add or remove authentication methods on an already authenticated channel
 * (docs/journeys/manage-auth-methods.md). Every wish first clears [selfServiceAcrFloor] and holds a
 * recent proof, as the `FRESH_PROOF` sub-journey (ADR-61). The wish stays parked in its state
 * meanwhile and is carried out once the proof is in.
 */
class ManageAuthMethodsStrategy : IntentStrategy<ManageAuthMethodsState> {

    override val intent = AuthIntent.MANAGE_AUTH_METHODS

    override fun initialState(ctx: JourneyContext): ManageAuthMethodsState = ManageAuthMethodsState.AddRequested

    override fun transition(state: ManageAuthMethodsState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is ManageAuthMethodsState.Wish -> when (event) {
                // Only this journey's own proof counts; any other sub-journey is never assumed to be it.
                is JourneyEvent.SubJourneyFinished ->
                    if (event.intent == AuthIntent.FRESH_PROOF) carryOut(state, ctx) else Transition.Cancel
                // A declined proof ends the wish instead of re-requesting it forever.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                // A removal or a retraction resumes in its wish, which then finishes.
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> requireFreshProof(state, ctx)
            }

            is ManageAuthMethodsState.Changing -> when (event) {
                is JourneyEvent.Abandoned -> Transition.Cancel
                is JourneyEvent.Completed -> Transition.Perform(proofAction(event, admittedAt = state.admittedAt), resumeState = state)
                // The new credential was adopted and replaced the old one.
                else -> Transition.Authenticated
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

    /**
     * What [wish] needs proven first. Nothing left to add needs no proof at all, and a change of
     * something that cannot be changed stops here, before anyone is asked for anything.
     */
    private fun requireFreshProof(wish: ManageAuthMethodsState.Wish, ctx: JourneyContext): Transition {
        val requiredAcr = when (wish) {
            ManageAuthMethodsState.AddRequested -> {
                if (offerEnrollment(ctx) is Transition.Authenticated) return Transition.Authenticated
                selfServiceAcrFloor(ctx.requireAccount())
            }
            is ManageAuthMethodsState.RemoveRequested, is ManageAuthMethodsState.RetractAttributeRequested -> selfServiceAcrFloor(ctx.requireAccount())
            is ManageAuthMethodsState.ChangeRequested -> changeFloor(
                changeTarget(wish, ctx) ?: return Transition.Abort(Text("Dieses Anmeldeverfahren lässt sich nicht ändern")),
                ctx,
            )
        }
        return Transition.RequireSubJourney(
            AuthIntent.FRESH_PROOF,
            seedWith = FreshProofState.forSubJourney(requiredAcr, ctx.currentAcr, FreshProofState.Purpose.MANAGE_METHODS),
            resumeWith = wish,
        )
    }

    /** What [wish] asked for, once the proof is in. */
    private fun carryOut(wish: ManageAuthMethodsState.Wish, ctx: JourneyContext): Transition = when (wish) {
        ManageAuthMethodsState.AddRequested -> offerEnrollment(ctx)
        is ManageAuthMethodsState.RemoveRequested -> Transition.Perform(Action.RevokeAuthMethod(wish.methodInstanceId), resumeState = wish)
        is ManageAuthMethodsState.RetractAttributeRequested -> Transition.Perform(Action.RetractAttribute(wish.attributeType), resumeState = wish)
        is ManageAuthMethodsState.ChangeRequested -> offerChange(wish, ctx)
    }

    /** The active instance [wish] names, if its method can be changed in place. */
    private fun changeTarget(wish: ManageAuthMethodsState.ChangeRequested, ctx: JourneyContext): AuthMethodView? =
        ctx.requireAccount().activeAuthenticationMethods
            .firstOrNull { it.id == wish.methodInstanceId }
            ?.takeIf { ctx.catalog.changeToolOf(it.method) != null }

    /** A change never lowers the credential: the session must hold what the old one was enrolled under. */
    private fun changeFloor(target: AuthMethodView, ctx: JourneyContext): AcrLevel =
        AcrLevel.max(selfServiceAcrFloor(ctx.requireAccount()), AcrLevel.parse(target.enrolledUnderAcr) ?: AcrLevel.NONE)

    private fun offerChange(wish: ManageAuthMethodsState.ChangeRequested, ctx: JourneyContext): Transition {
        val tool = changeTarget(wish, ctx)?.let { ctx.catalog.changeToolOf(it.method) }
        val offered = listOfNotNull(tool?.toolId).filter { it in ctx.availableTools }
        return if (offered.isEmpty()) Transition.Abort(Text("Dieses Anmeldeverfahren lässt sich hier nicht ändern"))
        else Transition.To(ManageAuthMethodsState.Changing(Offer(offered), wish.methodInstanceId, admittedAt = ctx.currentAcr))
    }

    /**
     * Voluntary enrollment on an authenticated channel. Binding the known device again is a
     * harmless no-op that keeps a new device credential reachable next time.
     */
    private fun proofAction(event: JourneyEvent.Completed, admittedAt: AcrLevel? = null): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome, admittedAt)
        is ToolOutcome.Completed.Identified, is ToolOutcome.Completed.Authenticated, is ToolOutcome.Completed.Approved, is ToolOutcome.Completed.Attested ->
            event.notOffered("MANAGE")
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
