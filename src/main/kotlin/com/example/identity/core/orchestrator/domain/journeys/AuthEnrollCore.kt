package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.texts.Text
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.toEnrollAbortMessage
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolId

/**
 * The logic [FastAccessJourney] and [RegisterJourney] share for [AuthChoice] and [Enrolling]. A
 * stateless helper, not a base class, so each journey's states stay readable from its own file.
 * `resumeAtStart` is the caller's own start state, for when a RE_IDENTIFY sub-journey returns.
 */
internal object AuthEnrollCore {

    /** After a proof: done, another factor via [AuthChoice], or else [offerEnrollment]. */
    fun afterProof(ctx: JourneyContext, resumeAtStart: JourneyState): Transition {
        val account = ctx.requireAccount()
        if (ctx hasReached ctx.acrFloor) return Transition.Authenticated

        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) return Transition.To(AuthChoice(Offer(candidates)))
        // An existing account that logs in is never blocked on a missing confirmed email
        // (docs/04-orchestrierung.md #5).
        return offerEnrollment(account, ctx, emailObligation = false, resumeAtStart)
    }

    /**
     * After declining an auth offer: the account's remaining auth tools without the [declined] ones,
     * or [whenNone] when nothing provable is left (or there is no account yet).
     */
    inline fun remainingAuthOr(ctx: JourneyContext, declined: Set<ToolId>, whenNone: () -> Transition): Transition {
        val remaining = ctx.account?.let { CandidateTools.forAuth(it, ctx.acrFloor, ctx) - declined }.orEmpty()
        return if (remaining.isNotEmpty()) Transition.To(AuthChoice(Offer(remaining))) else whenNone()
    }

    /**
     * [emailObligation] is normally discharged before any enrollment ([confirmEmail]). It is
     * re-checked here for the case that no attesting tool was available back then. Only
     * [RegisterJourney] passes `true`. [enforceEnrollmentFloor] as in [offerEnrollment].
     */
    fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean, resumeAtStart: JourneyState, enforceEnrollmentFloor: Boolean = true): Transition {
        val account = ctx.requireAccount()
        if (emailObligation) confirmEmail(account, ctx)?.let { return it }
        if (!(ctx canReach ctx.acrFloor) || !(ctx hasReached ctx.acrFloor)) {
            return offerEnrollment(account, ctx, emailObligation, resumeAtStart, enforceEnrollmentFloor)
        }
        return Transition.Authenticated
    }

    /**
     * The confirmed address as the first mandatory step of a registration. It is account
     * infrastructure that unlocks candidates (lookup tools, `enroll-password`), so it must come
     * before the first enrollment offer. `null` when already confirmed or no attesting tool is
     * available, so the caller does not dead-end.
     */
    fun confirmEmail(account: AccountProfile, ctx: JourneyContext): Transition? {
        if (account.emailConfirmed) return null
        return CandidateTools.forAttestation(AttributeType.EMAIL, ctx).takeIf { it.isNotEmpty() }
            ?.let { Transition.To(RegisterState.ConfirmingEmail(Offer(it))) }
    }

    /**
     * Never offers a new method below [ENROLLMENT_FLOOR_ACR]: a credential keeps the level the
     * session had when it was enrolled (ADR-5), so a loa1 session would create a loa1 credential
     * forever that a later step-up rejects. Fixed at loa2, not the channel's floor. Below it, a fresh
     * identification is offered. "Enrollment zuerst" accepts the loa1 cap on purpose and passes
     * [enforceEnrollmentFloor] `false`: there the identification comes last, optionally.
     */
    fun offerEnrollment(
        account: AccountProfile,
        ctx: JourneyContext,
        emailObligation: Boolean,
        resumeAtStart: JourneyState,
        enforceEnrollmentFloor: Boolean = true
    ): Transition {
        if (enforceEnrollmentFloor && !(ctx hasReached ENROLLMENT_FLOOR_ACR)) {
            val reidentTarget = maxOf(ENROLLMENT_FLOOR_ACR, ctx.acrFloor)
            return reIdentifyOr(reidentTarget, ctx, resumeWith = resumeAtStart) {
                Transition.Abort(
                    Text("Fuer die Einrichtung eines neuen Anmeldeverfahrens ist eine frische Identifizierung (mindestens loa2) erforderlich, aktuell ist aber keine Identifizierungsmethode verfuegbar.")
                )
            }
        }

        val candidates = CandidateTools.forEnrollment(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) {
            return Transition.To(Enrolling(Offer(candidates), emailObligation = emailObligation))
        }
        return reIdentifyOr(ctx.acrFloor, ctx, resumeWith = resumeAtStart) {
            // Reachable here means a channel-local reason, e.g. every remaining tool disabled.
            Transition.Abort(ctx.policy.reachability(account, ctx.acrFloor).toEnrollAbortMessage())
        }
    }

    /**
     * The last REGISTER obligation (docs/04-orchestrierung.md #5): a run must not end below loa2,
     * which its own method management needs. While loa2 is out of reach and the active methods
     * cover fewer than two factor kinds, every enrollment adding a missing kind is offered. Each
     * one adds a kind, so the covered set only grows and the obligation cannot repeat forever.
     */
    fun secondFactorKindCandidates(account: AccountProfile, ctx: JourneyContext): List<ToolId> {
        if (ctx canReach ENROLLMENT_FLOOR_ACR) return emptyList()
        val covered = CandidateTools.factorKindsOf(account, ctx)
        if (covered.size >= 2) return emptyList()
        return CandidateTools.forMissingFactorKind(account, covered, ctx)
    }

    /**
     * Also the level a REGISTER run must leave the account able to reach, so it can still manage
     * its own methods.
     */
    val ENROLLMENT_FLOOR_ACR = AcrLevel.LOA2
}
