package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * The states of `WEB_SELECT_METHOD` (docs/journeys/web-select-method.md): [SelectMethod] offers every
 * Keycloak-usable tool as one `selectMethod` step, narrowed by its declined tools until a proof closes
 * the gap or nothing is left; [Start] builds that offer, at the beginning and when a step-up returns
 * from `RE_IDENTIFY`.
 */
sealed interface WebSelectMethodState : JourneyState {

    data class SelectMethod(
        override val offer: Offer,
        /**
         * The account was known when this offer was built (a Web step-up): wording and trace only.
         * Which account a proof names is decided at execution time (`accountOfProof`).
         */
        val accountAlreadyKnown: Boolean
    ) : WebSelectMethodState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        override val selectionContext: String get() = "auth"
        // A step-up must say why it asks again. Keycloak shows the known e-mail address where the
        // heading would be, so the description carries the reason on its own.
        override val selectionTitle: Text get() =
            if (accountAlreadyKnown) Text("Erhöhte Sicherheit erforderlich") else Text("Anmeldeverfahren wählen")
        override val selectionDescription: Text get() =
            if (accountAlreadyKnown) {
                Text("Sie sind bereits angemeldet. Dieser Bereich verlangt aber mehr Sicherheit: Bestätigen Sie Ihre Anmeldung mit einem weiteren Verfahren.")
            } else {
                Text("Wählen Sie, wie Sie sich anmelden möchten.")
            }
        override val logDetail: Map<String, Any?> get() = mapOf("accountAlreadyKnown" to accountAlreadyKnown)
    }

    /**
     * Where a run begins, and where a step-up resumes after `RE_IDENTIFY`
     * (docs/journeys/web-select-method.md): no offer of its own, because taken-over evidence or the
     * fresh identification may already close the gap, and otherwise the offer is built anew.
     */
    data object Start : WebSelectMethodState, ToolFreeState
}
