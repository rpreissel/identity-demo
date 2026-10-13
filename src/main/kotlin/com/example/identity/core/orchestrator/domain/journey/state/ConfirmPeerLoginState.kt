package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.AuthIntent

/**
 * Approve or decline a Web-channel login waiting on this account's peer approval
 * (docs/journeys/confirm-peer-login.md). A cold entry and an authenticated channel both start at
 * [Requested] and end the same way: the channel is AUTHENTICATED. Whether a channel opened only for
 * this confirmation stays signed in is the app's question, not a state here (ADR-60).
 */
sealed interface ConfirmPeerLoginState : JourneyState {

    /**
     * The wish, and the state parked while the FRESH_PROOF sub-journey runs (as in
     * [ManageAuthMethodsState.AddRequested]). Without an account (cold entry, no device link) the
     * strategy aborts instead of falling into registration. No extra "confirm this?" prompt: the
     * step-up screen explains the request ([StepUpState.Reason.PEER_LOGIN]) and offers "Abbrechen".
     */
    data object Requested : ConfirmPeerLoginState, ToolFreeState {
        override val selectionContext: String get() = "enrollment"
    }

    /**
     * loa2 satisfied: the peer-approval tools are the candidates (today only `approve-qr`). An
     * [OfferingState], so the usual skip-the-selection rule applies to a single offer.
     */
    data class Confirming(override val offer: Offer) : ConfirmPeerLoginState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Web-Login bestätigen")
        override val selectionDescription: Text
            get() = Text("Ein Browser möchte sich mit Ihrem Konto anmelden. Bestätigen Sie das nur, wenn Sie diesen Login selbst ausgelöst haben.")
    }
}
