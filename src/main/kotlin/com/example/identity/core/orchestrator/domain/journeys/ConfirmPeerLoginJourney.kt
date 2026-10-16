package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.peerApprovalCandidates
import com.example.identity.core.orchestrator.domain.journey.state.ProofPurpose
import com.example.identity.core.orchestrator.domain.journey.JourneyWithoutInput
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.OpensAppChannel
import com.example.identity.core.orchestrator.domain.journey.StartableWhenAuthenticated
import com.example.identity.core.orchestrator.domain.journey.StartingAccount
import com.example.identity.core.orchestrator.domain.journey.DeviceLink
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
import com.example.identity.core.orchestrator.domain.journey.notExpectedIn
import com.example.identity.core.orchestrator.domain.journey.Transition
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.ConfirmPeerLoginState
import com.example.identity.contract.tool_api.claims.AcrLevel

/**
 * Approve or decline a Web-channel `auth-qr`/`auth-qr-lookup` pairing
 * (docs/journeys/confirm-peer-login.md). The session must prove loa2 and hold a recent proof before
 * it may vouch for a login elsewhere: the `FRESH_PROOF` sub-journey, without a way out through
 * re-identification (ADR-61). A cold App scanning the QR opens a channel with it, an authenticated
 * channel starts it; both reach the same gate. Without a known account it aborts; it never falls
 * back to identification or registration.
 */
object ConfirmPeerLoginJourney : JourneyWithoutInput<ConfirmPeerLoginState> {

    override val name = "CONFIRM_PEER_LOGIN"
    override val startPoints = listOf(
        OpensAppChannel(startsWith = StartingAccount.LINKED_TO_DEVICE, onSuccess = DeviceLink.LEAVE_UNCHANGED),
        StartableWhenAuthenticated,
    )
    override val stateRoot = ConfirmPeerLoginState::class

    override val firstState = ConfirmPeerLoginState.Requested

    override fun transition(state: ConfirmPeerLoginState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is ConfirmPeerLoginState.Requested -> when (event) {
                JourneyEvent.Started -> requireFreshProof(ctx)
                is JourneyEvent.SubJourneyFinished -> Transition.To(confirming(ctx))
                else -> event.notExpectedIn(state)
            }

            is ConfirmPeerLoginState.Confirming -> when (event) {
                // The approval ran; the channel stays signed in. A channel that logged in only for
                // the approval is asked by the app whether to log out again (ADR-60).
                JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> event.notExpectedIn(state)
            }
        }

    /**
     * loa2 and a recent proof for the channel's account (I-34), never lowered for a never-identified
     * account. That account is what `approve-qr` approves for. Without one (a cold entry on an
     * unlinked device) it aborts: this journey offers no identification or registration. Nor does
     * it ask for a proof it could not use, with no approving tool on offer.
     */
    private fun requireFreshProof(ctx: JourneyContext): Transition {
        if (ctx.account == null) return Transition.Abort(Text("Dieses Gerät ist noch keinem Konto zugeordnet. Bitte melden Sie sich zuerst in der App an."))
        if (ctx.peerApprovalCandidates().isEmpty()) return Transition.Abort(Text("Eine Anmeldung lässt sich auf diesem Gerät gerade nicht bestätigen."))
        return Transition.RequireSubJourney(
            FreshProofJourney.before(ProofPurpose.PEER_LOGIN, REQUIRED_ACR),
            resumeWith = ConfirmPeerLoginState.Requested,
        )
    }

    private fun confirming(ctx: JourneyContext) = ConfirmPeerLoginState.Confirming(Offer(ctx.peerApprovalCandidates()))

    val REQUIRED_ACR = AcrLevel.LOA2
}
