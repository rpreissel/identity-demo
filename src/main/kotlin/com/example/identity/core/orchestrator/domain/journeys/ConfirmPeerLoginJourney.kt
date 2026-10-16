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
 * channel starts it; both reach the same gate. Only a device linked to the account may vouch for it
 * (I-33): otherwise it aborts, and never falls back to identification or registration.
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
                is JourneyEvent.SubJourneyFinished -> offerApproval(ctx)
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
     * loa2 and a recent proof, never lowered for a never-identified account. Never asked for when
     * this device may not vouch anyway: this journey offers no identification or registration.
     */
    private fun requireFreshProof(ctx: JourneyContext): Transition {
        val refusal = refusalOf(ctx)
        if (refusal != null) return Transition.Abort(refusal)
        return Transition.RequireSubJourney(
            FreshProofJourney.before(ProofPurpose.PEER_LOGIN, REQUIRED_ACR),
            resumeWith = ConfirmPeerLoginState.Requested,
        )
    }

    /** The approval itself. Checked again: the device may have been linked elsewhere during the proof. */
    private fun offerApproval(ctx: JourneyContext): Transition {
        val refusal = refusalOf(ctx)
        if (refusal != null) return Transition.Abort(refusal)
        return Transition.To(ConfirmPeerLoginState.Confirming(Offer(ctx.peerApprovalCandidates())))
    }

    /** Why this device may not vouch for a login right now, or null when it may. */
    private fun refusalOf(ctx: JourneyContext): Text? = when {
        // I-33 (docs/invarianten.md): only a device linked to the account vouches for it.
        !ctx.isDeviceLinkedToAccount() -> NOT_LINKED
        ctx.peerApprovalCandidates().isEmpty() -> NO_APPROVAL_HERE
        else -> null
    }

    /** This device is durably linked to the account in hand; a channel without an account never is. */
    private fun JourneyContext.isDeviceLinkedToAccount(): Boolean =
        linkedAccountId != null && linkedAccountId == account?.accountId

    val REQUIRED_ACR = AcrLevel.LOA2

    private val NOT_LINKED = Text("Dieses Gerät ist nicht mit Ihrem Konto verknüpft. Eine Anmeldung bestätigen kann nur ein Gerät, das mit dem Konto verknüpft ist.")

    private val NO_APPROVAL_HERE = Text("Eine Anmeldung lässt sich auf diesem Gerät gerade nicht bestätigen.")
}
