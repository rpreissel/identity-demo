package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.declineTool
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState
import com.example.identity.contract.tool_api.ToolId

/**
 * Into a login on this device as fast as possible, and in a way that works again next time
 * (docs/journeys/fast-access.md).
 */
class FastAccessStrategy : IntentStrategy<FastAccessState> {

    override val intent: AuthIntent = AuthIntent.FAST_ACCESS

    override fun initialState(ctx: JourneyContext): FastAccessState = FastAccessState.Start

    override fun transition(state: FastAccessState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is FastAccessState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> AuthEnrollCore.afterProof(ctx, resumeAtStart = FastAccessState.Start)
                // No new evidence - re-deriving would just re-request the same sub-journey again.
                is JourneyEvent.SubJourneyCancelled -> Transition.Cancel
                else -> firstOffer(ctx)
            }
            is FastAccessState.PreferredAuth -> when (event) {
                is JourneyEvent.Abandoned -> afterAuthDeclined(ctx, alreadyDeclined = setOf(state.toolId))
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                else -> AuthEnrollCore.afterProof(ctx, resumeAtStart = FastAccessState.Start)
            }

            is AuthChoice -> when (event) {
                is JourneyEvent.Abandoned -> declineTool(state, event.tool.toolId, ctx) { afterAuthDeclined(ctx, it) }
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                else -> AuthEnrollCore.afterProof(ctx, resumeAtStart = FastAccessState.Start)
            }

            is Enrolling -> when (event) {
                is JourneyEvent.Abandoned -> reoffer(state)
                is JourneyEvent.Completed -> Transition.Perform(AuthEnrollCore.proofAction(event), resumeState = state)
                // FAST_ACCESS never sets emailObligation, so this never reaches ConfirmingEmail.
                else -> AuthEnrollCore.afterEnrollment(ctx, state.emailObligation, resumeAtStart = FastAccessState.Start)
            }
        }

    // Offers -------------------------------------------------------------------

    private fun firstOffer(ctx: JourneyContext): Transition {
        val account = ctx.account ?: return requireRegister()
        CandidateTools.preferredDeviceAuth(account, ctx)?.let { return Transition.To(FastAccessState.PreferredAuth(it)) }
        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        return if (candidates.isNotEmpty()) Transition.To(AuthChoice(Offer(candidates))) else requireRegister()
    }

    /** Nothing (or nothing else) provable is left on this device: hand off to a fresh identification. */
    private fun afterAuthDeclined(ctx: JourneyContext, alreadyDeclined: Set<ToolId>): Transition {
        val account = ctx.account
        if (account != null) {
            val remaining = CandidateTools.forAuth(account, ctx.acrFloor, ctx) - alreadyDeclined
            if (remaining.isNotEmpty()) {
                return Transition.To(AuthChoice(Offer(remaining)))
            }
        }
        return requireRegister()
    }

    /**
     * FAST_ACCESS never identifies anyone itself; REGISTER runs as a precondition. Resuming at
     * [FastAccessState.Start] re-checks satisfaction, so a rediscovered, already-equipped account
     * is simply done.
     */
    private fun requireRegister(): Transition = Transition.RequireSubJourney(
        AuthIntent.REGISTER,
        seedWith = RegisterState.forSubJourney(),
        resumeWith = FastAccessState.Start
    )
}
