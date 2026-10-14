package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.state.DeviceLinkQuestion
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.ANSWER_ACCEPT
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.IntentStrategy
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.LookupLoginState
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState
import com.example.identity.core.orchestrator.domain.journey.toAuthAbortMessage

/**
 * Log into an existing account without a paired device (docs/journeys/lookup-login.md): the user
 * names an identifier and proves a credential. There is no identification state, and the device
 * link arises only from [DeviceLinkQuestion], after the user agrees.
 */
class LookupLoginStrategy : IntentStrategy<LookupLoginState> {

    override val intent = AuthIntent.LOOKUP_LOGIN
    override val stateRoots = listOf(LookupLoginState::class)

    override fun initialState(ctx: JourneyContext): LookupLoginState = LookupLoginState.Start

    override fun transition(state: LookupLoginState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is LookupLoginState.Start -> when (event) {
                // Re-check whether the fresh proof already closes the gap before offering again.
                is JourneyEvent.SubJourneyFinished -> settleOrRaise(ctx)
                else -> {
                    // Every tool that resolves the account itself; authCandidates would need an
                    // account, which does not exist yet.
                    val tools = CandidateTools.forLookupLogin(ctx)
                    if (tools.isEmpty()) Transition.Abort(Text("Es gibt kein Anmeldeverfahren, das nicht an ein Gerät gebunden ist"))
                    else Transition.To(LookupLoginState.Credential(Offer(tools)))
                }
            }

            // The first proof has no account yet, so a lookup tool resolves it; the executor
            // decides that from the tool's role (accountOfProof), not this state. A further factor
            // runs against the account bound by the first one; giving it up cannot finish anyway,
            // since the floor is still unmet.
            is LookupLoginState.Credential -> proving(state, event, ctx)
            is LookupLoginState.AdditionalFactor -> proving(state, event, ctx)

            // The journey ends either way; only ANSWER_ACCEPT links the device.
            is DeviceLinkQuestion -> state.deviceLinkAnswered(event, whenDeclined = { Transition.Authenticated }) { Transition.Authenticated }
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
        if (ctx.policy.isSatisfied(ctx.evidence, ctx.acrFloor, account)) {
            // Unlinked: offer the link. Linked elsewhere: warn before rebinding. Linked here:
            // nothing to ask.
            return when (ctx.linkedAccountId) {
                null -> Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.OFFER))
                account.accountId -> Transition.Authenticated
                else -> Transition.To(DeviceLinkQuestion(DeviceLinkQuestion.Wording.REBIND))
            }
        }
        val candidates = CandidateTools.forAuth(account, ctx.acrFloor, ctx)
        if (candidates.isNotEmpty()) {
            return Transition.To(LookupLoginState.AdditionalFactor(Offer(candidates)))
        }
        return reIdentifyOr(ctx.acrFloor, ctx, resumeWith = LookupLoginState.Start) {
            Transition.Abort(ctx.policy.reachability(account, ctx.acrFloor).toAuthAbortMessage())
        }
    }
}
