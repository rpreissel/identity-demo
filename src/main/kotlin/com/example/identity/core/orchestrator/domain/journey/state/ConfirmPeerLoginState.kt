package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.core.orchestrator.domain.AuthIntent

/**
 * Approve or decline a Web-channel login waiting on this account's peer approval
 * (docs/journeys/confirm-peer-login.md). A cold entry and an authenticated channel both start at
 * [Requested]. [startedAuthenticated] is set once at entry; it decides whether finishing asks about
 * logging out again ([OfferLogout]).
 */
sealed interface ConfirmPeerLoginState : JourneyState {

    /**
     * The wish before the loa2 gate, and the state parked while a STEP_UP sub-journey runs (as in
     * [ManageAuthMethodsState.AddRequested]). Without an account (cold entry, no device link) the
     * strategy aborts instead of falling into registration. No extra "confirm this?" prompt: the
     * step-up screen explains the request ([StepUpState.Reason.PEER_LOGIN]) and offers "Abbrechen".
     */
    data class Requested(val startedAuthenticated: Boolean) : ConfirmPeerLoginState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    /**
     * Re-prove any one active factor, fresh. Reached only when the channel already had loa2 of
     * unknown age, not after this journey's own step-up. As in [DeleteAccountState.ConfirmationRequired]:
     * a possibly hijacked session must not vouch for a foreign login on old evidence alone.
     */
    data class ConfirmationRequired(
        val startedAuthenticated: Boolean,
        override val offer: Offer
    ) : ConfirmPeerLoginState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Web-Login bestätigen – Anmeldeverfahren bestätigen")
        override val selectionDescription: Text?
            get() = Text("Bevor Sie den Web-Login bestätigen, bestätigen Sie bitte noch einmal eines Ihrer Anmeldeverfahren.")
    }

    /**
     * loa2 satisfied: the peer-approval tools are the candidates (today only `confirm-qr-login`). An
     * [OfferingState], so the usual skip-the-selection rule applies to a single offer.
     */
    data class Confirming(
        val startedAuthenticated: Boolean,
        override val offer: Offer
    ) : ConfirmPeerLoginState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Web-Login bestätigen")
        override val selectionDescription: Text
            get() = Text("Ein Browser möchte sich mit Ihrem Konto anmelden. Bestätigen Sie das nur, wenn Sie diesen Login selbst ausgelöst haben.")
    }

    /**
     * Only reached when [startedAuthenticated] was false: the channel logged in just for this
     * confirmation. It asks, because neither silently logging out nor silently staying in is
     * what every user wants.
     */
    data object OfferLogout : ConfirmPeerLoginState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val question: Question
            get() = Question.Confirm(
                title = Text("Jetzt abmelden?"),
                description = Text("Dieses Gerät war vor der Bestätigung nicht angemeldet - nur für diese eine Bestätigung wurde es kurz angemeldet. Jetzt wieder abmelden, oder angemeldet bleiben?"),
                confirmLabel = Text("Abmelden"),
                cancelLabel = Text("Angemeldet bleiben"),
                destructive = false
            )
    }
}
