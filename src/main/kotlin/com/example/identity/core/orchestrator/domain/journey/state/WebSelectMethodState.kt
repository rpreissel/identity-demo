package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * The states of `WEB_SELECT_METHOD` (docs/journeys/web-select-method.md): [SelectLoginMethod] (no
 * account yet) or [SelectStepUpMethod] (a Web step-up) offers every Keycloak-usable tool as one
 * `selectMethod` step, narrowed by its declined tools until a proof closes the gap or nothing is
 * left; [Start] builds that offer, at the beginning and when a step-up returns from `RE_IDENTIFY`.
 */
sealed interface WebSelectMethodState : JourneyState {

    /**
     * Nobody known yet: the tools that find their subject from the input. Which account or
     * invitation a proof names is decided at execution time (`accountOfProof`).
     */
    data class SelectLoginMethod(override val offer: Offer) : WebSelectMethodState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        override val screen: Screen get() = Screen.SELECT_AUTH_METHOD
        override val selectionTitle: Text get() = Text("Anmeldeverfahren wählen")
        override val selectionDescription: Text get() = Text("Wählen Sie, wie Sie sich anmelden möchten.")
    }

    /**
     * A Web step-up: the account is signed in, the page asks for more. It must say why it asks
     * again; Keycloak shows the known e-mail address where the heading would be, so the
     * description carries the reason on its own.
     */
    data class SelectStepUpMethod(override val offer: Offer) : WebSelectMethodState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        override val screen: Screen get() = Screen.SELECT_AUTH_METHOD
        override val selectionTitle: Text get() = Text("Erhöhte Sicherheit erforderlich")
        override val selectionDescription: Text get() =
            Text("Sie sind bereits angemeldet. Dieser Bereich verlangt aber mehr Sicherheit: Bestätigen Sie Ihre Anmeldung mit einem weiteren Verfahren.")
    }

    /**
     * Where a run begins, and where a step-up resumes after `RE_IDENTIFY`
     * (docs/journeys/web-select-method.md): no offer of its own, because taken-over evidence or the
     * fresh identification may already close the gap, and otherwise the offer is built anew.
     */
    data object Start : WebSelectMethodState, ToolFreeState
}
