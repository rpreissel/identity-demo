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
import com.example.identity.core.orchestrator.domain.journey.AccountJourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.notExpectedIn
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
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
                JourneyEvent.Started -> offerLookup(ctx)
                // Back from RE_IDENTIFY: the fresh proof may already close the gap.
                is JourneyEvent.SubJourneyFinished -> settleOrRaise(ctx)
                else -> event.notExpectedIn(state)
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

    /** Every tool that resolves the account itself; authCandidates would need an account, which does not exist yet. */
    private fun offerLookup(ctx: JourneyContext): Transition =
        offer(ctx.lookupLoginCandidates(), LookupLoginState::Credential)
            ?: Transition.Abort(Text("Es gibt kein Anmeldeverfahren, das nicht an ein Gerät gebunden ist"))

    private fun proving(state: OfferingState, event: JourneyEvent, ctx: JourneyContext): Transition = when (event) {
        JourneyEvent.ActionCompleted -> settleOrRaise(ctx)
        is JourneyEvent.OfferExhausted -> Transition.Cancel
        else -> event.notExpectedIn(state)
    }

    /**
     * Checks the channel's acrFloor, since finishing sets AUTHENTICATED unconditionally. There is no
     * enrollment fallback: an account that cannot reach the floor must not grow new credentials on
     * an unproven device. Re-identification stays available; it adds no lasting credential.
     */
    private fun settleOrRaise(ctx: JourneyContext): Transition {
        val known = ctx.requireAccount()
        if (known hasReached known.acrFloor) return askAboutDeviceLink(known)
        val furtherFactors = known.authCandidates()
        if (furtherFactors.isNotEmpty()) return Transition.To(LookupLoginState.AdditionalFactor(Offer(furtherFactors)))
        return ReIdentifyJourney.toReachOr(known.acrFloor, known, resumeWith = LookupLoginState.Start) {
            Transition.Abort(known.policy.reachability(known.account, known.acrFloor).toAuthAbortMessage())
        }
    }

    /** Unlinked: offer the link. Linked elsewhere: warn before rebinding. Linked here: nothing to ask. */
    private fun askAboutDeviceLink(ctx: AccountJourneyContext): Transition = when (ctx.linkedAccountId) {
        null -> Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.OFFER))
        ctx.account.accountId -> Transition.Authenticated
        else -> Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.REBIND))
    }
}
