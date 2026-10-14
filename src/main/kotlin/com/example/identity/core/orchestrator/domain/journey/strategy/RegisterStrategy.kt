package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.state.DeviceLinkQuestion
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.JourneyFeatureFlag
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.policy.Reachability

/**
 * REGISTER (docs/journeys/register.md): identify first, then confirm the address and set up methods.
 * With [JourneyFeatureFlag.REGISTER_ENROLL_FIRST] the experiment "Enrollment zuerst"
 * (docs/journeys/register-enroll-first.md) runs instead: confirm the address, set up methods
 * without identification, and only then offer the identification once. Both share every state;
 * the journey keeps the switch from its start, so a running registration never changes variant.
 */
class RegisterStrategy : IntentStrategy<RegisterState> {

    override val intent: AuthIntent = AuthIntent.REGISTER
    override val stateRoots = listOf(RegisterState::class)

    override fun initialState(ctx: JourneyContext): RegisterState = RegisterState.Start

    private val JourneyContext.enrollFirst get() = JourneyFeatureFlag.REGISTER_ENROLL_FIRST in featureFlags

    override fun transition(state: RegisterState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is RegisterState.Start -> when (event) {
                // A RE_IDENTIFY sub-journey from offerEnrollment finished: re-check.
                is JourneyEvent.SubJourneyFinished -> AuthEnrollCore.afterProof(ctx, resumeAtStart = RegisterState.Start)
                else -> if (ctx.enrollFirst) offerEmailFirst(ctx) else offerIdentification(ctx)
            }

            // The closing, optional identification came back, however it ended: the journey is done.
            is RegisterState.Closing -> finishOrOfferRebind(ctx)

            is RegisterState.Identifying -> when (event) {
                // Declining the last identification offer is giving up on the journey, not an error.
                is JourneyEvent.OfferExhausted -> Transition.Cancel
                // No isSatisfied check: identification alone clears most floors and would let a
                // run finish without a durable credential.
                else -> afterIdentification(ctx)
            }

            is AuthChoice -> when (event) {
                // Nothing else provable is left: identification is the only way forward.
                is JourneyEvent.OfferExhausted -> AuthEnrollCore.remainingAuthOr(ctx, event.declined) { offerIdentification(ctx) }
                // No factor-kind or email obligation: a rediscovered, set-up account finishing here
                // is an ordinary login, not a fresh registration.
                else -> AuthEnrollCore.afterProof(ctx, resumeAtStart = RegisterState.Start)
            }

            is DeviceLinkQuestion -> when (state.wording) {
                // Asked at the end ("Enrollment zuerst"): the registration is done either way;
                // declined, the account stays usable through the lookup tools.
                DeviceLinkQuestion.Wording.REBIND -> state.deviceLinkAnswered(event, whenDeclined = { Transition.Authenticated }) { Transition.Authenticated }
                // Asked right after the identification: declining is giving up, since no method is
                // offered on a device that stays someone else's.
                else -> state.deviceLinkAnswered(event, whenDeclined = { Transition.Cancel }) { afterIdentification(ctx) }
            }

            is RegisterState.Assigning -> when (event) {
                // Backing out is the "not now" (DeclinePolicy.OPTIONAL): the run carries on as prospect (ADR-10).
                is JourneyEvent.OfferExhausted -> continueAfterAssignment(ctx)
                // Back through afterIdentification: the assignment may have moved this run to
                // another account (ADR-20), which must pass the same checks (device link, existing
                // methods). The assignment offer is not repeated, since the person is bound now.
                is JourneyEvent.ActionCompleted -> afterIdentification(ctx)
                else -> continueAfterAssignment(ctx)
            }

            // Mandatory steps: a completed tool is adopted, and only the next step differs.
            is RegisterState.ConfirmingEmail -> afterEmailConfirmed(ctx)
            is Enrolling -> afterEnrollment(ctx, state.emailObligation)
            // The email obligation is already discharged here.
            is RegisterState.SecondFactorKindObligation -> afterEnrollment(ctx, emailObligation = false)
        }

    // Offers -------------------------------------------------------------------

    /**
     * "Enrollment zuerst" begins with the address, which is account infrastructure (lookup tools,
     * `enroll-password`). Skipped if no attesting tool is available.
     */
    private fun offerEmailFirst(ctx: JourneyContext): Transition {
        val candidates = CandidateTools.forAttestation(AttributeType.EMAIL, ctx)
        return if (candidates.isNotEmpty()) Transition.To(RegisterState.ConfirmingEmail(Offer(candidates))) else afterEmailConfirmed(ctx)
    }

    /**
     * After the address: the cascade goes on. "Enrollment zuerst" may still have no account here;
     * it arises with the first method, from a free choice among all enrollment tools.
     */
    private fun afterEmailConfirmed(ctx: JourneyContext): Transition {
        if (ctx.account != null) return afterEnrollment(ctx, emailObligation = false)
        val candidates = CandidateTools.forEnrollment(account = null, ctx.acrFloor, ctx)
        return if (candidates.isEmpty()) Transition.Abort(Text("Kein Anmeldeverfahren verfuegbar"))
        else Transition.To(Enrolling(Offer(candidates), emailObligation = true))
    }

    private fun offerIdentification(ctx: JourneyContext): Transition {
        val idents = CandidateTools.forIdentification(ctx)
        return if (idents.isEmpty()) {
            Transition.Abort(Text("Kein Identifizierungsverfahren verfuegbar"))
        } else {
            Transition.To(RegisterState.Identifying(Offer(idents)))
        }
    }

    private fun afterIdentification(ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        // Device linked to another account ("Zweitaccount", docs/04-orchestrierung.md #2): ask
        // first, before any method is offered.
        if (ctx.deviceLinkedElsewhere()) {
            return Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.REBIND_OR_CANCEL))
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
     * finishes only after the email obligation, this is checked last. "Enrollment zuerst" accepts a
     * loa1 credential (no enrollment floor) and ends in [offerClosingIdentification].
     */
    private fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean): Transition {
        val base = AuthEnrollCore.afterEnrollment(ctx, emailObligation, resumeAtStart = RegisterState.Start, enforceEnrollmentFloor = !ctx.enrollFirst)
        if (base != Transition.Authenticated) return base
        val candidates = AuthEnrollCore.secondFactorKindCandidates(ctx.requireAccount(), ctx)
        if (candidates.isNotEmpty()) return Transition.To(RegisterState.SecondFactorKindObligation(Offer(candidates)))
        return if (ctx.enrollFirst) offerClosingIdentification(ctx) else base
    }

    /**
     * "Enrollment zuerst": every obligation is discharged, so the identification is offered once,
     * optionally. Declining or having nothing to offer finishes the registration.
     */
    private fun offerClosingIdentification(ctx: JourneyContext): Transition =
        // The default wording ("erneut", "nicht erreichbar") is wrong for a new account.
        reIdentifyOr(ctx.acrFloor, ctx, resumeWith = RegisterState.Closing, wording = ReIdentifyState.Wording.OPTIONAL_IDENTIFICATION, optional = true) {
            finishOrOfferRebind(ctx)
        }

    /**
     * The last question of "Enrollment zuerst": a device linked to another account was not bound
     * implicitly, and only now may the account have an identity to name. A Web channel has no
     * device, so `linkedAccountId` is null and this falls through.
     */
    private fun finishOrOfferRebind(ctx: JourneyContext): Transition =
        if (ctx.account != null && ctx.deviceLinkedElsewhere()) Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.REBIND))
        else Transition.Authenticated
}
