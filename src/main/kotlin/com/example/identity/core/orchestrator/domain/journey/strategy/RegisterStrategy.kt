package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.journey.declineTool
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.policy.Reachability
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.ToolId

/**
 * REGISTER, ident first (docs/journeys/register.md). Reached only through
 * [RegisterDispatchStrategy]; [RegisterEnrollFirstStrategy] is the other variant.
 */
class RegisterStrategy : IntentStrategy<RegisterState> {

    override val intent: AuthIntent = AuthIntent.REGISTER

    override fun initialState(ctx: JourneyContext): RegisterState = RegisterState.Start

    override fun transition(state: RegisterState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is RegisterState.Start -> when (event) {
                // A RE_IDENTIFY sub-journey from offerEnrollment finished: re-check.
                is JourneyEvent.SubJourneyFinished -> AuthEnrollCore.afterProof(ctx, resumeAtStart = RegisterState.Start)
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> offerIdentification(ctx)
            }

            is RegisterState.Identifying -> when (event) {
                // Abandoning the last identification offer is giving up on the journey, not an error.
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) { Transition.Cancel }
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                // No isSatisfied check: identification alone clears most floors and would let a
                // run finish without a durable credential.
                else -> afterIdentification(ctx)
            }

            is AuthChoice -> when (event) {
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) { afterAuthDeclined(ctx, it) }
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                // No factor-kind or email obligation: a rediscovered, set-up account finishing here
                // is an ordinary login, not a fresh registration.
                else -> AuthEnrollCore.afterProof(ctx, resumeAtStart = RegisterState.Start)
            }

            is RegisterState.ConfirmDeviceRebind -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ANSWER_ACCEPT -> Transition.Perform(Action.LinkDevice, resumeState = state)
                    ANSWER_DECLINE -> Transition.Cancel
                    else -> event.notUnderstood("ConfirmDeviceRebind")
                }
                is JourneyEvent.ActionCompleted -> afterIdentification(ctx)
                else -> error("ConfirmDeviceRebind only accepts JourneyEvent.Answered")
            }

            is RegisterState.Assigning -> when (event) {
                // Backing out is the "not now": the run carries on as prospect (ADR-10).
                is JourneyEvent.Abandoned -> continueAfterAssignment(ctx)
                // The executor reads the account in hand at execution time.
                is JourneyEvent.Completed if event.outcome is ToolOutcome.Completed.Identified ->
                    Transition.Perform(Action.RecordIdentification(event.tool, event.outcome), resumeState = state)
                is JourneyEvent.Completed -> event.notOffered("the assignment step")
                // Back through afterIdentification: the assignment may have moved this run to
                // another account (ADR-20), which must pass the same checks (device link, existing
                // methods). The assignment offer is not repeated, since the person is bound now.
                is JourneyEvent.ActionCompleted -> afterIdentification(ctx)
                else -> continueAfterAssignment(ctx)
            }

            // Mandatory states: backing out re-offers, a completed tool is adopted, and only the
            // next step differs.
            is RegisterState.ConfirmingEmail -> mandatory(state, event) { afterEnrollment(ctx, emailObligation = false) }
            is Enrolling -> mandatory(state, event) { afterEnrollment(ctx, state.emailObligation) }
            // The email obligation is already discharged here.
            is RegisterState.SecondFactorKindObligation -> mandatory(state, event) { afterEnrollment(ctx, emailObligation = false) }
        }

    private inline fun mandatory(state: OfferingState, event: JourneyEvent, next: () -> Transition): Transition = when (event) {
        is JourneyEvent.Abandoned -> reoffer(state)
        is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
        else -> next()
    }

    // Offers -------------------------------------------------------------------

    private fun offerIdentification(ctx: JourneyContext): Transition {
        val idents = CandidateTools.forIdentification(ctx)
        return if (idents.isEmpty()) {
            Transition.Abort(Text("Kein Identifizierungsverfahren verfuegbar"))
        } else {
            Transition.To(RegisterState.Identifying(Offer(idents)))
        }
    }

    /** Nothing else provable is left: identification is the only way forward. */
    private fun afterAuthDeclined(ctx: JourneyContext, alreadyDeclined: Set<ToolId>): Transition {
        val account = ctx.account
        if (account != null) {
            val remaining = CandidateTools.forAuth(account, ctx.acrFloor, ctx) - alreadyDeclined
            if (remaining.isNotEmpty()) {
                return Transition.To(AuthChoice(Offer(remaining)))
            }
        }
        return offerIdentification(ctx)
    }

    private fun afterIdentification(ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        // Device linked to another account ("Zweitaccount", docs/04-orchestrierung.md #2): ask
        // first, before any method is offered.
        if (ctx.linkedAccountId != null && ctx.linkedAccountId != account.accountId) {
            return Transition.To(RegisterState.ConfirmDeviceRebind)
        }
        // An account found again may already have what it needs: prove an existing method.
        if (ctx.policy.reachability(account, ctx.acrFloor) is Reachability.Reachable) {
            val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
            if (candidates.isNotEmpty()) return Transition.To(AuthChoice(Offer(candidates)))
        }
        // Identified but not bound in the register (ADR-18): offer the correlation step before any
        // enrollment. No yes/no prompt in front; the form says what the number is for.
        if (account.isUnidentified) offerAssignment(ctx)?.let { return it }
        return continueAfterAssignment(ctx)
    }

    /** The correlation offer itself, or null when nothing is offerable (tool disabled meanwhile). */
    private fun offerAssignment(ctx: JourneyContext): Transition? =
        CandidateTools.forAssignment(ctx).takeIf { it.isNotEmpty() }
            ?.let { Transition.To(RegisterState.Assigning(Offer(it))) }

    /** The rest of a registration, once the person-assignment question is settled either way. */
    private fun continueAfterAssignment(ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        // After the AuthChoice branch: the email obligation belongs to a genuine registration,
        // not to a rediscovered account logging in (docs/04-orchestrierung.md #5).
        // emailObligation stays true: without an attesting tool confirmEmail yields nothing, and
        // the obligation is retried after enrollment.
        return AuthEnrollCore.confirmEmail(account, ctx)
            ?: AuthEnrollCore.offerEnrollment(account, ctx, emailObligation = true, resumeAtStart = RegisterState.Start)
    }

    /**
     * Wraps [AuthEnrollCore.afterEnrollment] and intercepts only [Transition.Authenticated] to apply
     * the factor-kind obligation ([RegisterState.SecondFactorKindObligation]). Since the shared code
     * finishes only after the email obligation, this is checked last.
     */
    private fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean): Transition {
        val base = AuthEnrollCore.afterEnrollment(ctx, emailObligation, resumeAtStart = RegisterState.Start)
        if (base != Transition.Authenticated) return base
        val candidates = AuthEnrollCore.secondFactorKindCandidates(ctx.requireAccount(), ctx)
        return if (candidates.isNotEmpty()) Transition.To(RegisterState.SecondFactorKindObligation(Offer(candidates))) else base
    }
}
