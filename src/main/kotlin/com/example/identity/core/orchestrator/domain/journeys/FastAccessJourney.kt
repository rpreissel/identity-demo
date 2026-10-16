package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.offer
import com.example.identity.core.orchestrator.domain.journey.authCandidates
import com.example.identity.core.orchestrator.domain.journey.deviceKeyCandidate
import com.example.identity.core.orchestrator.domain.journey.JourneyWithoutInput
import com.example.identity.core.orchestrator.domain.journey.OpensAppChannel
import com.example.identity.core.orchestrator.domain.journey.StartingAccount
import com.example.identity.core.orchestrator.domain.journey.DeviceLink
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.AuthChoice
import com.example.identity.core.orchestrator.domain.journey.state.Enrolling
import com.example.identity.core.orchestrator.domain.journey.state.FastAccessState

/**
 * Into a login on this device as fast as possible, and in a way that works again next time
 * (docs/journeys/fast-access.md).
 */
object FastAccessJourney : JourneyWithoutInput<FastAccessState> {

    override val name = "FAST_ACCESS"
    override val startPoints = listOf(OpensAppChannel(startsWith = StartingAccount.LINKED_TO_DEVICE, onSuccess = DeviceLink.LINK_WITHOUT_ASKING))
    override val stateRoot = FastAccessState::class

    private val toRequiredLevel = PathToRequiredLevel(afterIdentificationBackTo = FastAccessState.Start)

    override val firstState = FastAccessState.Start

    override fun transition(state: FastAccessState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is FastAccessState.Start -> when (event) {
                // Back from REGISTER or RE_IDENTIFY: re-check whether that already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> toRequiredLevel.nextAfterProof(ctx)
                else -> firstOffer(ctx)
            }
            is FastAccessState.PreferredAuth -> when (event) {
                is JourneyEvent.Abandoned -> toRequiredLevel.nextWithoutOr(setOf(state.toolId), ctx) { requireRegister() }
                else -> toRequiredLevel.nextAfterProof(ctx)
            }

            is AuthChoice -> when (event) {
                // Nothing else provable is left on this device: hand off to a fresh identification.
                is JourneyEvent.OfferExhausted -> toRequiredLevel.nextWithoutOr(event.declined, ctx) { requireRegister() }
                else -> toRequiredLevel.nextAfterProof(ctx)
            }

            is Enrolling -> toRequiredLevel.nextAfterEnrollment(ctx)
        }

    // Offers -------------------------------------------------------------------

    /** The device's key first, then the account's other methods; without either, a registration. */
    private fun firstOffer(ctx: JourneyContext): Transition {
        if (ctx.account == null) return requireRegister()
        return ctx.deviceKeyCandidate()?.let { Transition.To(FastAccessState.PreferredAuth(it)) }
            ?: offer(ctx.authCandidates(), ::AuthChoice)
            ?: requireRegister()
    }

    /**
     * FAST_ACCESS never identifies anyone itself; REGISTER runs as a precondition. Resuming at
     * [FastAccessState.Start] re-checks satisfaction, so a rediscovered, already-equipped account
     * is simply done.
     */
    private fun requireRegister(): Transition = Transition.RequireSubJourney(
        RegisterJourney.start(),
        resumeWith = FastAccessState.Start
    )
}
