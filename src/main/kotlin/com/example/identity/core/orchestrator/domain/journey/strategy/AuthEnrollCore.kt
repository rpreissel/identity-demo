package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.texts.Text
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.toEnrollAbortMessage
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.ReIdentifyState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.core.orchestrator.domain.policy.Reachability
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolOutcome

/**
 * The logic [FastAccessStrategy] and [RegisterStrategy] share for [AuthChoice] and [Enrolling]. A
 * stateless helper, not a base class, so each intent's states stay readable from its own file.
 * `resumeAtStart` is the caller's own start state, for when a RE_IDENTIFY sub-journey returns.
 */
internal object AuthEnrollCore {

    /** An outcome maps to one Action; what it means is decided by its handler from live context. */
    fun proofAction(event: JourneyEvent.Completed): Action = when (val outcome = event.outcome) {
        // The account may be brand new or an existing one found again by KVNR; both are the
        // same decision here, which is why registration needs no state of its own.
        is ToolOutcome.Completed.Identified -> Action.RecordIdentification(event.tool, outcome)
        // A real credential now exists on this device, so recognizing the device costs
        // nothing and saves the next login: bind it.
        is ToolOutcome.Completed.Enrolled -> Action.AdoptCredential(event.tool, outcome)
        // A device-bound tool never resolves the account itself - it could only have been
        // offered once the account was already known.
        is ToolOutcome.Completed.Authenticated -> Action.AcceptProof(event.tool, outcome)
        // Claims only: no credential, and the device is not bound, since nothing lives on it.
        is ToolOutcome.Completed.Attested -> Action.AdoptAttestation(event.tool, outcome)
        is ToolOutcome.Completed.Approved -> error("${event.tool.toolId} is not offered by FAST_ACCESS/REGISTER")
    }

    /** After a proof: done, another factor via [AuthChoice], or else [offerEnrollment]. */
    fun afterProof(ctx: JourneyContext, resumeAtStart: JourneyState): Transition {
        val account = ctx.requireAccount()
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) return Transition.Authenticated

        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) return Transition.To(AuthChoice(Offer(candidates)))
        // An existing account that logs in is never blocked on a missing confirmed email
        // (docs/04-orchestrierung.md #8).
        return offerEnrollment(account, ctx, emailObligation = false, resumeAtStart)
    }

    /**
     * [emailObligation] is normally discharged before any enrollment ([confirmEmail]). It is
     * re-checked here for the case that no attesting tool was available back then. Only
     * [RegisterStrategy] passes `true`.
     */
    fun afterEnrollment(ctx: JourneyContext, emailObligation: Boolean, resumeAtStart: JourneyState): Transition {
        val account = ctx.requireAccount()
        if (emailObligation) confirmEmail(account, ctx)?.let { return it }
        val reachable = ctx.policy.reachability(account, ctx.acrFloor) is Reachability.Reachable
        if (!reachable || !ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            return offerEnrollment(account, ctx, emailObligation = false, resumeAtStart)
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
     * identification is offered. "Enrollment zuerst" accepts the loa1 cap on purpose.
     */
    fun offerEnrollment(account: AccountProfile, ctx: JourneyContext, emailObligation: Boolean, resumeAtStart: JourneyState): Transition {
        if (!ctx.policy.isSatisfied(ctx.evidence, ENROLLMENT_FLOOR_ACR, account)) {
            val reidentTarget = AcrLevel.max(ENROLLMENT_FLOOR_ACR, ctx.acrFloor)
            return if (CandidateTools.forReIdentification(reidentTarget, ctx).isNotEmpty()) {
                Transition.RequireSubJourney(
                    AuthIntent.RE_IDENTIFY,
                    seedWith = ReIdentifyState.forSubJourney(reidentTarget, ctx.currentAcr),
                    resumeWith = resumeAtStart
                )
            } else {
                Transition.Abort(
                    Text("Fuer die Einrichtung eines neuen Anmeldeverfahrens ist eine frische Identifizierung (mindestens loa2) erforderlich, aktuell ist aber keine Identifizierungsmethode verfuegbar.")
                )
            }
        }

        val candidates = CandidateTools.forEnrollment(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) {
            return Transition.To(Enrolling(Offer(candidates), emailObligation = emailObligation))
        }
        return if (CandidateTools.forReIdentification(ctx.acrFloor, ctx).isNotEmpty()) {
            Transition.RequireSubJourney(
                AuthIntent.RE_IDENTIFY,
                seedWith = ReIdentifyState.forSubJourney(ctx.acrFloor, ctx.currentAcr),
                resumeWith = resumeAtStart
            )
        } else {
            // Reachable here means a channel-local reason, e.g. every remaining tool disabled.
            Transition.Abort(ctx.policy.reachability(account, ctx.acrFloor).toEnrollAbortMessage())
        }
    }

    /**
     * The last REGISTER obligation (docs/04-orchestrierung.md #8): a run must not end below loa2,
     * which its own method management needs. While loa2 is out of reach and the active methods
     * cover fewer than two factor kinds, every enrollment adding a missing kind is offered. Each
     * one adds a kind, so the covered set only grows and the obligation cannot repeat forever.
     */
    fun secondFactorKindCandidates(account: AccountProfile, ctx: JourneyContext): List<ToolId> {
        if (ctx.policy.reachability(account, ENROLLMENT_FLOOR_ACR) is Reachability.Reachable) return emptyList()
        val covered = CandidateTools.factorKindsOf(account, ctx)
        if (covered.size >= 2) return emptyList()
        return CandidateTools.forMissingFactorKind(account, covered, ctx)
    }

    /**
     * Also the level a REGISTER run must leave the account able to reach, so it can still manage
     * its own methods.
     */
    val ENROLLMENT_FLOOR_ACR = AcrLevel.LOA2

    /**
     * On a mandatory state, backing out of a tool is not declining it: the obligation stands, so
     * the full choice comes back. Only fallback states accumulate `declined`.
     */
    fun reoffer(state: JourneyState): Transition = Transition.To(state.withActive(null))
}
