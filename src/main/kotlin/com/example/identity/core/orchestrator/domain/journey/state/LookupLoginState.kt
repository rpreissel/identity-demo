package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * States of LOOKUP_LOGIN (docs/journeys/lookup-login.md). There is no `Identifying`: without a known
 * account, identification would create or adopt one, which is not a login. Once the account is
 * known, a fresh identification runs as the `RE_IDENTIFY` sub-journey, which only confirms that
 * account.
 */
sealed interface LookupLoginState : JourneyState {

    data object Start : LookupLoginState, ToolFreeState

    data class Credential(
        override val offer: Offer
    ) : LookupLoginState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Anmeldung – Konto bestätigen")
        override val selectionDescription: Text get() = Text("Geben Sie Ihre Zugangsdaten ein, um sich mit Ihrem bestehenden Konto anzumelden.")
    }

    /**
     * One credential is proven but the channel's acrFloor is not reached yet. The account is now
     * known, so the offer comes from `AuthPolicy.authCandidates`, not the lookup-only set. Without
     * this state the channel would reach AUTHENTICATED below its required level.
     */
    data class AdditionalFactor(
        override val offer: Offer
    ) : LookupLoginState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Zusätzlicher Faktor erforderlich")
        override val selectionDescription: Text get() = Text("Ihre bisherige Anmeldung reicht für das geforderte Sicherheitsniveau nicht aus. Bitte bestätigen Sie einen weiteren Faktor.")
    }
}
