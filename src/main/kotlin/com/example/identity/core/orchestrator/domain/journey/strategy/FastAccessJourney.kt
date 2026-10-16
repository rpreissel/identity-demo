package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.OpensAppChannel
import com.example.identity.core.orchestrator.domain.journey.StartingAccount
import com.example.identity.core.orchestrator.domain.journey.DeviceLink
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState
import com.example.identity.core.orchestrator.domain.journey.state.RegisterState

/**
 * Into a login on this device as fast as possible, and in a way that works again next time
 * (docs/journeys/fast-access.md).
 */
object FastAccessJourney : Journey<FastAccessState> {

    override val name = "FAST_ACCESS"
    override val startPoints = listOf(OpensAppChannel(startsWith = StartingAccount.LINKED_TO_DEVICE, onSuccess = DeviceLink.LINK_WITHOUT_ASKING))
    override val stateRoots = listOf(FastAccessState::class)

    override fun initialState(ctx: JourneyContext): FastAccessState = FastAccessState.Start

    override fun transition(state: FastAccessState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is FastAccessState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> AuthEnrollCore.afterProof(ctx, resumeAtStart = FastAccessState.Start)
                else -> firstOffer(ctx)
            }
            is FastAccessState.PreferredAuth -> when (event) {
                is JourneyEvent.Abandoned -> AuthEnrollCore.remainingAuthOr(ctx, declined = setOf(state.toolId)) { requireRegister() }
                else -> AuthEnrollCore.afterProof(ctx, resumeAtStart = FastAccessState.Start)
            }

            is AuthChoice -> when (event) {
                // Nothing else provable is left on this device: hand off to a fresh identification.
                is JourneyEvent.OfferExhausted -> AuthEnrollCore.remainingAuthOr(ctx, event.declined) { requireRegister() }
                else -> AuthEnrollCore.afterProof(ctx, resumeAtStart = FastAccessState.Start)
            }

            // FAST_ACCESS never sets emailObligation, so this never reaches ConfirmingEmail.
            is Enrolling -> AuthEnrollCore.afterEnrollment(ctx, state.emailObligation, resumeAtStart = FastAccessState.Start)
        }

    // Offers -------------------------------------------------------------------

    private fun firstOffer(ctx: JourneyContext): Transition {
        val account = ctx.account ?: return requireRegister()
        CandidateTools.preferredDeviceAuth(account, ctx)?.let { return Transition.To(FastAccessState.PreferredAuth(it)) }
        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        return if (candidates.isNotEmpty()) Transition.To(AuthChoice(Offer(candidates))) else requireRegister()
    }

    /**
     * FAST_ACCESS never identifies anyone itself; REGISTER runs as a precondition. Resuming at
     * [FastAccessState.Start] re-checks satisfaction, so a rediscovered, already-equipped account
     * is simply done.
     */
    private fun requireRegister(): Transition = Transition.RequireSubJourney(
        RegisterJourney,
        seedWith = RegisterState.forSubJourney(),
        resumeWith = FastAccessState.Start
    )
}
