package com.example.identity.core.orchestrator.domain.journeys

import com.example.identity.core.orchestrator.domain.journey.JourneyWithoutInput
import com.example.identity.core.orchestrator.domain.journey.state.FreshProofState
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.CandidateTools
import com.example.identity.core.orchestrator.domain.journey.OpensAppChannel
import com.example.identity.core.orchestrator.domain.journey.StartableWhenAuthenticated
import com.example.identity.core.orchestrator.domain.journey.StartingAccount
import com.example.identity.core.orchestrator.domain.journey.DeviceLink
import com.example.identity.core.orchestrator.domain.journey.JourneyContext
import com.example.identity.core.orchestrator.domain.journey.JourneyEvent
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
    override val stateRoots = listOf(ConfirmPeerLoginState::class)

    override val firstState = ConfirmPeerLoginState.Requested

    override fun transition(state: ConfirmPeerLoginState, event: JourneyEvent, ctx: JourneyContext): Transition =
        when (state) {
            is ConfirmPeerLoginState.Requested -> when (event) {
                is JourneyEvent.SubJourneyFinished -> Transition.To(confirming(ctx))
                else -> requireFreshProof(ctx)
            }

            is ConfirmPeerLoginState.Confirming -> when (event) {
                // The approval ran; the channel stays signed in. A channel that logged in only for
                // the approval is asked by the app whether to log out again (ADR-60).
                is JourneyEvent.ActionCompleted -> Transition.Authenticated
                else -> error("Confirming does not understand $event")
            }
        }

    private fun confirming(ctx: JourneyContext) = ConfirmPeerLoginState.Confirming(Offer(CandidateTools.forPeerApproval(ctx)))

    /**
     * Without an account (a cold entry on an unlinked device) it aborts; this journey offers no
     * registration. Otherwise loa2 and a recent proof, never lowered for a never-identified account.
     */
    private fun requireFreshProof(ctx: JourneyContext): Transition {
        if (ctx.account == null) return Transition.Abort(Text("Dieses Gerät ist noch keinem Konto zugeordnet - bitte zuerst regulär anmelden."))
        return Transition.RequireSubJourney(
            FreshProofJourney.before(FreshProofState.Purpose.PEER_LOGIN, REQUIRED_ACR),
            resumeWith = ConfirmPeerLoginState.Requested,
        )
    }

    val REQUIRED_ACR = AcrLevel.LOA2
}
