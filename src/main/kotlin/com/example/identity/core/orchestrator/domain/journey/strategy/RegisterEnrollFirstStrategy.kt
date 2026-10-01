package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.texts.Text
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.journey.ANSWER_DECLINE
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterEnrollFirstState
import com.example.identity.core.orchestrator.domain.journey.toEnrollAbortMessage
import com.example.identity.core.orchestrator.domain.policy.Reachability
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome

/**
 * REGISTER, "Enrollment zuerst" (docs/journeys/register.md). Reached only through
 * [RegisterDispatchStrategy]. Mandatory order: email confirmation, then SMS, then the optional
 * identification ([offerIdentificationOrFinish]). A device linked to another account is asked
 * about at the end ([finishOrOfferRebind]), once the account may have an identity to name.
 */
class RegisterEnrollFirstStrategy : IntentStrategy<RegisterEnrollFirstState> {

    override val intent: AuthIntent = AuthIntent.REGISTER

    override fun initialState(ctx: JourneyContext): RegisterEnrollFirstState = RegisterEnrollFirstState.EnrollFirstStart

    override fun transition(state: RegisterEnrollFirstState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is RegisterEnrollFirstState.EnrollFirstStart -> when (event) {
                // The optional identification came back, however it ended: the journey is done.
                is JourneyEvent.SubJourneyFinished, is JourneyEvent.SubJourneyCancelled -> finishOrOfferRebind(ctx)
                // Fresh start without an account; it is created on the first completed enrollment.
                else -> offerEmailConfirmation(ctx)
            }

            // Every offering state here is mandatory: backing out re-offers, a completed tool is
            // adopted, and only the next step differs.
            is RegisterEnrollFirstState.EnrollFirstAttestingEmail -> mandatory(state, event) { offerSmsEnrollment(ctx) }
            is RegisterEnrollFirstState.EnrollFirstEnrollingSms -> mandatory(state, event) { afterForcedEnrollment(ctx) }
            is RegisterEnrollFirstState.EnrollFirstEnrolling -> mandatory(state, event) { afterEnrollment(ctx, emailObligation = true) }
            is RegisterEnrollFirstState.EnrollFirstConfirmingEmail -> mandatory(state, event) { afterEnrollment(ctx, emailObligation = false) }
            // The email obligation is already discharged here.
            is RegisterEnrollFirstState.EnrollFirstSecondFactorKindObligation -> mandatory(state, event) { afterEnrollment(ctx, emailObligation = false) }

            is RegisterEnrollFirstState.EnrollFirstConfirmDeviceRebind -> when (event) {
                is JourneyEvent.Answered -> when (event.answer) {
                    ANSWER_ACCEPT -> Transition.Perform(Action.LinkDevice, resumeState = state)
                    // The account stays usable through the lookup tools, just not recognized here.
                    ANSWER_DECLINE -> Transition.Authenticated
                    else -> event.notUnderstood("EnrollFirstConfirmDeviceRebind")
                }
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> error("EnrollFirstConfirmDeviceRebind only accepts JourneyEvent.Answered")
            }
        }

    private inline fun mandatory(state: OfferingState, event: JourneyEvent, next: () -> Transition): Transition = when (event) {
        is JourneyEvent.Abandoned -> reoffer(state)
        is JourneyEvent.Completed -> Transition.Perform(adoptCredential(event), resumeState = state)
        else -> next()
    }

    private fun adoptCredential(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome)
        // Confirming the address: claims and anchor, no credential, no device binding.
        is ToolOutcome.Completed.Attested -> Action.AdoptAttestation(event.tool, outcome)
        else -> event.notOffered("REGISTER (Enrollment zuerst) - only ENROLLMENT and ATTESTATION tools ever are")
    }

    /**
     * Mandatory step 1: confirm the address, which is account infrastructure (lookup tools,
     * `enroll-password`), not a login method. Falls through to step 2 if no attesting tool is available.
     */
    private fun offerEmailConfirmation(ctx: JourneyContext): Transition {
        val candidates = CandidateTools.forAttestation(AttributeType.EMAIL, ctx)
        return if (candidates.isNotEmpty()) Transition.To(RegisterEnrollFirstState.EnrollFirstAttestingEmail(Offer(candidates))) else offerSmsEnrollment(ctx)
    }

    /** Mandatory step 2; falls through to [afterForcedEnrollment] if no SMS tool is available. */
    private fun offerSmsEnrollment(ctx: JourneyContext): Transition {
        val candidates = enrollmentCandidatesFor(SMS_METHOD, ctx)
        return if (candidates.isNotEmpty()) Transition.To(RegisterEnrollFirstState.EnrollFirstEnrollingSms(Offer(candidates))) else afterForcedEnrollment(ctx)
    }

    /**
     * Both mandatory steps are done or skipped. With an account, the obligation cascade continues;
     * without one (neither tool was available), a free choice among all enrollment tools follows.
     */
    private fun afterForcedEnrollment(ctx: JourneyContext): Transition =
        if (ctx.account != null) afterEnrollment(ctx, emailObligation = true) else offerEnrollment(ctx)

    private fun offerEnrollment(ctx: JourneyContext): Transition {
        // No account exists yet. The candidate query reads only methods and emailConfirmed, so an
        // unpersisted blank placeholder is enough.
        val blankAccount = AccountProfile(accountId = AccountId(-1), personId = null, authenticationMethods = emptyList())
        val candidates = CandidateTools.forEnrollment(blankAccount, ctx.acrFloor, ctx)
        return if (candidates.isNotEmpty()) {
            Transition.To(RegisterEnrollFirstState.EnrollFirstEnrolling(Offer(candidates)))
        } else {
            Transition.Abort(Text("Kein Anmeldeverfahren verfuegbar"))
        }
    }

    /**
     * The same cascade as `AuthEnrollCore.afterEnrollment` (sufficient method, confirmed email,
     * second factor kind), ending in the optional identification offer.
     */
    private fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean): Transition {
        val account = ctx.requireAccount()
        val reachability = ctx.policy.reachability(account, ctx.acrFloor)
        if (reachability !is Reachability.Reachable || !ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            val candidates = CandidateTools.forEnrollment(account, ctx.acrFloor, ctx)
            return if (candidates.isNotEmpty()) {
                Transition.To(RegisterEnrollFirstState.EnrollFirstEnrolling(Offer(candidates)))
            } else {
                // Reachable here means a channel-local reason, e.g. every remaining tool disabled.
                Transition.Abort(reachability.toEnrollAbortMessage())
            }
        }
        if (emailObligation && !account.emailConfirmed) {
            CandidateTools.forAttestation(AttributeType.EMAIL, ctx).takeIf { it.isNotEmpty() }
                ?.let { return Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmingEmail(Offer(it))) }
        }
        // Same rule as RegisterStrategy. Without identification loa2 is never reachable: every
        // method sits at loa1, and the bump is capped by enrolledUnderAcr (ADR-5).
        AuthEnrollCore.secondFactorKindCandidates(account, ctx).takeIf { it.isNotEmpty() }
            ?.let { return Transition.To(RegisterEnrollFirstState.EnrollFirstSecondFactorKindObligation(Offer(it))) }
        return offerIdentificationOrFinish(ctx)
    }

    /**
     * Every obligation is discharged: identification is offered once, optionally, as a RE_IDENTIFY
     * sub-journey. Declining or having nothing to offer finishes the registration.
     */
    private fun offerIdentificationOrFinish(ctx: JourneyContext): Transition =
        if (CandidateTools.forReIdentification(ctx.acrFloor, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(
                AuthIntent.RE_IDENTIFY,
                // The default wording ("erneut", "nicht erreichbar") is wrong for a new account.
                seedWith = ReIdentifyState.forSubJourney(
                    targetAcr = ctx.acrFloor,
                    startingAcr = ctx.currentAcr,
                    wording = ReIdentifyState.Wording.OPTIONAL_IDENTIFICATION
                ),
                resumeWith = RegisterEnrollFirstState.EnrollFirstStart
            )
        } else {
            finishOrOfferRebind(ctx)
        }

    /**
     * The last question: a device linked to another account was not bound implicitly
     * ([RegisterEnrollFirstState.EnrollFirstConfirmDeviceRebind]). A Keycloak channel has no
     * device, so `linkedAccountId` is null and this falls through.
     */
    private fun finishOrOfferRebind(ctx: JourneyContext): Transition {
        val accountId = ctx.account?.accountId
        val linkedElsewhere = ctx.linkedAccountId != null && ctx.linkedAccountId != accountId
        return if (accountId != null && linkedElsewhere) {
            Transition.To(RegisterEnrollFirstState.EnrollFirstConfirmDeviceRebind)
        } else {
            Transition.Authenticated
        }
    }

    /** Enrollment tools for one method name, found through the catalog, never a fixed toolId. */
    private fun enrollmentCandidatesFor(method: String, ctx: JourneyContext): List<ToolId> =
        ctx.catalog.descriptors()
            .filter { it.role == ToolRole.ENROLLMENT && it.method == method }
            .map { it.toolId }
            .filter { it in ctx.availableTools }

    private companion object {
        const val SMS_METHOD = "sms"
    }
}
