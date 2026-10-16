package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.offer
import com.example.identity.core.orchestrator.domain.journey.authCandidates
import com.example.identity.core.orchestrator.domain.journey.lookupLoginCandidates
import com.example.identity.core.orchestrator.domain.journey.JourneyWithoutInput
import com.example.identity.core.orchestrator.domain.journey.state.DeviceLinkQuestion
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.OpensAppChannel
import com.example.identity.core.orchestrator.domain.journey.StartingAccount
import com.example.identity.core.orchestrator.domain.journey.DeviceLink
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.LookupLoginState
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.domain.journey.toAuthAbortMessage

/**
 * Log into an existing account without a paired device (docs/journeys/lookup-login.md): the user
 * names an identifier and proves a credential. There is no identification state, and the device
 * link arises only from [DeviceLinkQuestion], after the user agrees: people choose this journey
 * because they do not want to be recognized next time.
 */
object LookupLoginJourney : JourneyWithoutInput<LookupLoginState> {

    override val name = "LOOKUP_LOGIN"
    override val startPoints = listOf(OpensAppChannel(startsWith = StartingAccount.NONE, onSuccess = DeviceLink.ASK_BEFORE_LINKING))
    override val stateRoot = LookupLoginState::class

    override val firstState = LookupLoginState.Start

    override fun transition(state: LookupLoginState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is LookupLoginState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> settleOrRaise(ctx)
                else -> {
                    // Every tool that resolves the account itself; authCandidates would need an
                    // account, which does not exist yet.
                    offer(ctx.lookupLoginCandidates(), LookupLoginState::Credential)
                        ?: Transition.Abort(Text("Es gibt kein Anmeldeverfahren, das nicht an ein Gerät gebunden ist"))
                }
            }

            // The first proof has no account yet, so a lookup tool resolves it; the executor
            // decides that from the tool's role (accountOfProof), not this state. A further factor
            // runs against the account bound by the first one; giving it up cannot finish anyway,
            // since the floor is still unmet.
            is LookupLoginState.Credential -> proving(state, event, ctx)
            is LookupLoginState.AdditionalFactor -> proving(state, event, ctx)

            // The journey ends either way; only Answer.ACCEPT links the device.
            is DeviceLinkQuestion -> state.answered(event, whenDeclined = { Transition.Authenticated }) { Transition.Authenticated }
        }

    private fun proving(state: OfferingState, event: JourneyEvent, ctx: JourneyContext): Transition = when (event) {
        is JourneyEvent.OfferExhausted -> Transition.Cancel
        else -> settleOrRaise(ctx)
    }

    /**
     * Checks the channel's acrFloor, since finishing sets AUTHENTICATED unconditionally. There is no
     * enrollment fallback: an account that cannot reach the floor must not grow new credentials on
     * an unproven device. Re-identification stays available; it adds no lasting credential.
     */
    private fun settleOrRaise(ctx: JourneyContext): Transition {
        val account = ctx.requireAccount()
        if (ctx hasReached ctx.acrFloor) {
            // Unlinked: offer the link. Linked elsewhere: warn before rebinding. Linked here:
            // nothing to ask.
            return when (ctx.linkedAccountId) {
                null -> Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.OFFER))
                account.accountId -> Transition.Authenticated
                else -> Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.REBIND))
            }
        }
        return offer(ctx.authCandidates(), LookupLoginState::AdditionalFactor) ?: ReIdentifyJourney.toReachOr(ctx.acrFloor, ctx, resumeWith = LookupLoginState.Start) {
            Transition.Abort(ctx.policy.reachability(account, ctx.acrFloor).toAuthAbortMessage())
        }
    }
}
