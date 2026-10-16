package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.offer
import com.example.identity.core.orchestrator.domain.journey.authCandidates
import com.example.identity.core.orchestrator.domain.journey.enrollmentCandidates
import com.example.identity.core.orchestrator.domain.journey.state.ToolFreeState
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.toEnrollAbortMessage
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling

/**
 * The way FAST_ACCESS and REGISTER take to the channel's required level (`acrFloor`): the account's
 * own methods first ([AuthChoice]), then a new one ([Enrolling]), last a fresh identification
 * (RE_IDENTIFY), which returns to [afterIdentificationBackTo]. Each journey holds one, so its
 * states stay readable from its own file; what only REGISTER asks (a confirmed address, a second
 * factor kind) stays in [RegisterJourney].
 */
class PathToRequiredLevel(private val afterIdentificationBackTo: ToolFreeState) {

    /** After a proof: done, another of the account's methods, or a new one. */
    fun nextAfterProof(ctx: JourneyContext): Transition {
        if (ctx hasReached ctx.acrFloor) return Transition.Authenticated
        // An existing account that logs in is never blocked on a missing confirmed email
        // (docs/04-orchestrierung.md #5).
        return offer(ctx.authCandidates(), ::AuthChoice) ?: offerNewMethod(ctx)
    }

    /**
     * After an enrollment: done once the session has reached the level and the account can reach it
     * again on its own, else another new method. [identifyBeforeEnrolling] and [emailObligation] as
     * in [offerNewMethod].
     */
    fun nextAfterEnrollment(ctx: JourneyContext, identifyBeforeEnrolling: Boolean = true, emailObligation: Boolean = false): Transition {
        if (isReached(ctx)) return Transition.Authenticated
        return offerNewMethod(ctx, identifyBeforeEnrolling, emailObligation)
    }

    /** The session has reached the level, and the account can reach it again on its own. */
    fun isReached(ctx: JourneyContext): Boolean = ctx canReach ctx.acrFloor && ctx hasReached ctx.acrFloor

    /** The account's methods without the [declined] ones, or [otherwise] when none is left (or there is no account yet). */
    fun nextWithoutOr(declined: Set<ToolId>, ctx: JourneyContext, otherwise: () -> Transition): Transition {
        val remaining = if (ctx.account == null) emptyList() else ctx.authCandidates() - declined
        return offer(remaining, ::AuthChoice) ?: otherwise()
    }

    /**
     * A new method, or a fresh identification first when none can help. Never below
     * [NEW_METHOD_FLOOR] while [identifyBeforeEnrolling]: a credential keeps the level the session had
     * when it was enrolled (ADR-5), so a loa1 session would create a loa1 credential forever that a
     * later step-up rejects. "Enrollment zuerst" accepts that cap on purpose and passes `false`: there
     * the identification comes last, optionally. [emailObligation] is carried into [Enrolling] for
     * REGISTER, which re-checks the confirmed address after the enrollment.
     */
    fun offerNewMethod(ctx: JourneyContext, identifyBeforeEnrolling: Boolean = true, emailObligation: Boolean = false): Transition {
        val account = ctx.requireAccount()
        if (identifyBeforeEnrolling && !(ctx hasReached NEW_METHOD_FLOOR)) {
            return ReIdentifyJourney.toReachOr(maxOf(NEW_METHOD_FLOOR, ctx.acrFloor), ctx, resumeWith = afterIdentificationBackTo) {
                Transition.Abort(
                    Text("Fuer die Einrichtung eines neuen Anmeldeverfahrens ist eine frische Identifizierung (mindestens loa2) erforderlich, aktuell ist aber keine Identifizierungsmethode verfuegbar.")
                )
            }
        }
        val enrollments = ctx.enrollmentCandidates()
        if (enrollments.isNotEmpty()) return Transition.To(Enrolling(Offer(enrollments), emailObligation = emailObligation))
        return ReIdentifyJourney.toReachOr(ctx.acrFloor, ctx, resumeWith = afterIdentificationBackTo) {
            // Reachable here means a channel-local reason, e.g. every remaining tool disabled.
            Transition.Abort(ctx.policy.reachability(account, ctx.acrFloor).toEnrollAbortMessage())
        }
    }

    companion object {
        /**
         * The level a session needs before a new method is enrolled, fixed at loa2 rather than the
         * channel's floor. Also the level a REGISTER run must leave the account able to reach, so it
         * can still manage its own methods.
         */
        val NEW_METHOD_FLOOR = AcrLevel.LOA2
    }
}
