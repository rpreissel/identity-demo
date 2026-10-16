package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.offer
import com.example.identity.core.orchestrator.domain.journey.authCandidates
import com.example.identity.core.orchestrator.domain.journey.enrollmentCandidates
import com.example.identity.core.orchestrator.domain.journey.reIdentificationCandidates
import com.example.identity.core.orchestrator.domain.journey.identificationCandidates
import com.example.identity.core.orchestrator.domain.journey.assignmentCandidates
import com.example.identity.core.orchestrator.domain.journey.attestationCandidates
import com.example.identity.core.orchestrator.domain.journey.coveredFactorKinds
import com.example.identity.core.orchestrator.domain.journey.missingFactorKindCandidates
import com.example.identity.core.orchestrator.domain.journey.JourneyWithoutInput
import com.example.identity.core.orchestrator.domain.journey.state.DeviceLinkQuestion
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.JourneyFeatureFlag
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.core.orchestrator.domain.journey.OpensAppChannel
import com.example.identity.core.orchestrator.domain.journey.OpensWebChannel
import com.example.identity.core.orchestrator.domain.journey.StartingAccount
import com.example.identity.core.orchestrator.domain.journey.DeviceLink
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.AccountJourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.notExpectedIn
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.AfterEnrolling
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState

/**
 * REGISTER (docs/journeys/register.md): identify first, then confirm the address and set up methods.
 * With [JourneyFeatureFlag.REGISTER_ENROLL_FIRST] the experiment "Enrollment zuerst"
 * (docs/journeys/register-enroll-first.md) runs instead: confirm the address, set up methods
 * without identification, and only then offer the identification once. Both share every state;
 * the journey keeps the switch from its start, so a running registration never changes variant.
 */
object RegisterJourney : JourneyWithoutInput<RegisterState> {

    override val name = "REGISTER"
    override val startPoints = listOf(
        OpensAppChannel(startsWith = StartingAccount.NONE, onSuccess = DeviceLink.LINK_WITHOUT_ASKING),
        OpensWebChannel,
    )
    override val stateRoot = RegisterState::class

    override val firstState = RegisterState.Start

    private val toRequiredLevel = PathToRequiredLevel(afterIdentificationBackTo = RegisterState.Start)

    private val JourneyContext.enrollFirst get() = JourneyFeatureFlag.REGISTER_ENROLL_FIRST in featureFlags

    /** "Enrollment zuerst" enrolls at whatever level the session has; the identification comes last. */
    private val JourneyContext.beforeNewMethod
        get() = if (enrollFirst) BeforeNewMethod.ENROLL_AT_CURRENT_LEVEL else BeforeNewMethod.IDENTIFY_FIRST

    override fun transition(state: RegisterState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is RegisterState.Start -> when (event) {
                JourneyEvent.Started -> firstOffer(ctx)
                // A RE_IDENTIFY sub-journey before a new method finished: re-check.
                is JourneyEvent.SubJourneyFinished -> toRequiredLevel.settle(ctx.requireAccount())
                else -> event.notExpectedIn(state)
            }

            // The closing, optional identification came back, however it ended: the journey is done.
            is RegisterState.Closing -> when (event) {
                is JourneyEvent.SubJourneyFinished, is JourneyEvent.SubJourneyCancelled -> finishOrOfferRebind(ctx)
                else -> event.notExpectedIn(state)
            }

            is RegisterState.Identifying -> when (event) {
                // No isSatisfied check: identification alone clears most floors and would let a
                // run finish without a durable credential.
                JourneyEvent.ActionCompleted -> afterIdentification(ctx.requireAccount())
                // Declining the last identification offer is giving up on the journey, not an error.
                is JourneyEvent.OfferExhausted -> Transition.Cancel
                else -> event.notExpectedIn(state)
            }

            is AuthChoice -> when (event) {
                // No factor-kind or email obligation: a rediscovered, set-up account finishing here
                // is an ordinary login, not a fresh registration.
                JourneyEvent.ActionCompleted -> toRequiredLevel.settle(ctx.requireAccount())
                // Nothing else provable is left: identification is the only way forward.
                is JourneyEvent.OfferExhausted -> toRequiredLevel.nextWithoutOr(event.declined, ctx) { offerIdentification(ctx) }
                else -> event.notExpectedIn(state)
            }

            is DeviceLinkQuestion -> when (state.wording) {
                // Asked right after the identification: declining is giving up, since no method is
                // offered on a device that stays someone else's.
                DeviceLinkQuestion.Wording.REBIND_OR_CANCEL -> state.answered(event, whenDeclined = { Transition.Cancel }) { afterIdentification(ctx.requireAccount()) }
                // Asked at the end ("Enrollment zuerst"): the registration is done either way;
                // declined, the account stays usable through the lookup tools.
                DeviceLinkQuestion.Wording.REBIND -> state.answered(event, whenDeclined = { Transition.Authenticated }) { Transition.Authenticated }
                // A first link happens without asking (DeviceLink.LINK_WITHOUT_ASKING).
                DeviceLinkQuestion.Wording.OFFER -> error("REGISTER never offers a first device link")
            }

            is RegisterState.Assigning -> when (event) {
                // Back through afterIdentification: the assignment may have moved this run to
                // another account (ADR-20), which must pass the same checks (device link, existing
                // methods). The assignment offer is not repeated, since the person is bound now.
                JourneyEvent.ActionCompleted -> afterIdentification(ctx.requireAccount())
                // Backing out is the "not now" (DeclinePolicy.OPTIONAL): the run carries on as prospect (ADR-10).
                is JourneyEvent.OfferExhausted -> continueAfterAssignment(ctx.requireAccount())
                else -> event.notExpectedIn(state)
            }

            // Mandatory steps: a completed tool is adopted, and only the next step differs.
            is RegisterState.ConfirmingEmail -> when (event) {
                JourneyEvent.ActionCompleted -> afterEmailConfirmed(ctx)
                else -> event.notExpectedIn(state)
            }
            is Enrolling -> when (event) {
                JourneyEvent.ActionCompleted -> afterEnrollment(ctx.requireAccount(), state.then)
                else -> event.notExpectedIn(state)
            }
            // The email obligation is already discharged here.
            is RegisterState.SecondFactorKindObligation -> when (event) {
                JourneyEvent.ActionCompleted -> afterEnrollment(ctx.requireAccount(), AfterEnrolling.NEXT_STEP)
                else -> event.notExpectedIn(state)
            }
        }

    // Offers -------------------------------------------------------------------

    /**
     * Identification first, or the address under "Enrollment zuerst". Done at once only for an
     * account that has already proven the floor and can reach it again on its own: evidence taken
     * over from the Keycloak session (ADR-59). Any other known account registers like everyone,
     * with no shortcut through its methods; FAST_ACCESS hands over exactly when those do not help.
     */
    private fun firstOffer(ctx: JourneyContext): Transition {
        val known = ctx.withAccount
        return when {
            known != null && toRequiredLevel.isReached(known) -> Transition.Authenticated
            ctx.enrollFirst -> offerEmailFirst(ctx)
            else -> offerIdentification(ctx)
        }
    }

    /**
     * "Enrollment zuerst" begins with the address, which is account infrastructure (lookup tools,
     * `enroll-password`). Skipped if no attesting tool is available.
     */
    private fun offerEmailFirst(ctx: JourneyContext): Transition {
        return offer(ctx.attestationCandidates(AttributeType.EMAIL), RegisterState::ConfirmingEmail) ?: afterEmailConfirmed(ctx)
    }

    /**
     * After the address: the cascade goes on. "Enrollment zuerst" may still have no account here;
     * it arises with the first method, from a free choice among all enrollment tools.
     */
    private fun afterEmailConfirmed(ctx: JourneyContext): Transition {
        val known = ctx.withAccount
        if (known != null) return afterEnrollment(known, AfterEnrolling.NEXT_STEP)
        return offer(ctx.enrollmentCandidates()) { Enrolling(it, AfterEnrolling.CONFIRM_EMAIL_FIRST) }
            ?: Transition.Abort(Text("Kein Anmeldeverfahren verfuegbar"))
    }

    private fun offerIdentification(ctx: JourneyContext): Transition =
        offer(ctx.identificationCandidates(), RegisterState::Identifying)
            ?: Transition.Abort(Text("Kein Identifizierungsverfahren verfuegbar"))

    private fun afterIdentification(ctx: AccountJourneyContext): Transition {
        // Device linked to another account ("Zweitaccount", docs/04-orchestrierung.md #2): ask
        // first, before any method is offered.
        if (ctx.deviceLinkedElsewhere()) {
            return Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.REBIND_OR_CANCEL))
        }
        // An account found again may already have what it needs: prove an existing method.
        if (ctx canReach ctx.acrFloor) {
            val existingMethod = offer(ctx.authCandidates(), ::AuthChoice)
            if (existingMethod != null) return existingMethod
        }
        // Identified but not bound in the register (ADR-18): offer the correlation step before any
        // enrollment. No yes/no prompt in front; the form says what the number is for.
        if (ctx.account.isUnidentified) {
            val assignment = offerAssignment(ctx)
            if (assignment != null) return assignment
        }
        return continueAfterAssignment(ctx)
    }

    /** The correlation offer itself, or null when nothing is offerable (tool disabled meanwhile). */
    private fun offerAssignment(ctx: JourneyContext): Transition? =
        offer(ctx.assignmentCandidates(), RegisterState::Assigning)

    /** The rest of a registration, once the person-assignment question is settled either way. */
    private fun continueAfterAssignment(ctx: AccountJourneyContext): Transition {
        // After the AuthChoice branch: the email obligation belongs to a genuine registration,
        // not to a rediscovered account logging in (docs/04-orchestrierung.md #5).
        // The obligation stays: without an attesting tool confirmEmail yields nothing, and it is
        // retried after the enrollment.
        return confirmEmail(ctx)
            ?: toRequiredLevel.offerNewMethod(ctx, then = AfterEnrolling.CONFIRM_EMAIL_FIRST)
    }

    /**
     * The confirmed address as the first mandatory step of a registration. It is account
     * infrastructure that unlocks candidates (lookup tools, `enroll-password`), so it must come
     * before the first enrollment offer. `null` when already confirmed or no attesting tool is
     * available, so the caller does not dead-end.
     */
    private fun confirmEmail(ctx: AccountJourneyContext): Transition? {
        if (ctx.account.emailConfirmed) return null
        return offer(ctx.attestationCandidates(AttributeType.EMAIL), RegisterState::ConfirmingEmail)
    }

    /**
     * The next step on [toRequiredLevel], with REGISTER's own obligations around it. The email
     * obligation ([then]) is normally discharged before any enrollment ([confirmEmail]); it is
     * re-checked here for the case that no attesting tool was available back then. Once the level is reached, the factor-kind
     * obligation ([RegisterState.SecondFactorKindObligation]) comes last. "Enrollment zuerst" accepts
     * a loa1 credential (no identification before enrolling) and ends in [offerClosingIdentification].
     */
    private fun afterEnrollment(ctx: AccountJourneyContext, then: AfterEnrolling): Transition {
        if (then == AfterEnrolling.CONFIRM_EMAIL_FIRST) {
            val confirm = confirmEmail(ctx)
            if (confirm != null) return confirm
        }
        if (!toRequiredLevel.isReached(ctx)) return toRequiredLevel.offerNewMethod(ctx, ctx.beforeNewMethod, then)
        val secondKind = offer(secondFactorKindCandidates(ctx), RegisterState::SecondFactorKindObligation)
        if (secondKind != null) return secondKind
        return if (ctx.enrollFirst) offerClosingIdentification(ctx) else Transition.Authenticated
    }

    /**
     * The last REGISTER obligation (docs/04-orchestrierung.md #5): a run must not end below loa2,
     * which its own method management needs. While loa2 is out of reach and the active methods
     * cover fewer than two factor kinds, every enrollment adding a missing kind is offered. Each
     * one adds a kind, so the covered set only grows and the obligation cannot repeat forever.
     */
    private fun secondFactorKindCandidates(ctx: AccountJourneyContext): List<ToolId> {
        if (ctx canReach PathToRequiredLevel.NEW_METHOD_FLOOR) return emptyList()
        if (ctx.coveredFactorKinds().size >= 2) return emptyList()
        return ctx.missingFactorKindCandidates()
    }

    /**
     * "Enrollment zuerst": every obligation is discharged, so the identification is offered once,
     * optionally. Declining or having nothing to offer finishes the registration.
     */
    private fun offerClosingIdentification(ctx: JourneyContext): Transition =
        if (ctx.reIdentificationCandidates(ctx.acrFloor).isNotEmpty()) {
            Transition.RequireSubJourney(ReIdentifyJourney.offerOptionally(ctx.acrFloor), resumeWith = RegisterState.Closing, optional = true)
        } else {
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

    /** This device is durably linked to an account other than the one in hand (docs/04-orchestrierung.md #2, "Zweitaccount"). */
    private fun JourneyContext.deviceLinkedElsewhere(): Boolean = linkedAccountId != null && linkedAccountId != account?.accountId
}
