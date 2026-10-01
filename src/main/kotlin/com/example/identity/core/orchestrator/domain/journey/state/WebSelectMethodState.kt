package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * The single state of `WEB_SELECT_METHOD` (docs/04-orchestrierung.md Abschnitt 3): offers every
 * kc-usable tool as one `selectMethod` step, narrowed by [declined] until a proof closes the gap or
 * nothing is left.
 */
sealed interface WebSelectMethodState : JourneyState {

    /**
     * Whether the account was already known when this offer was built (a Web step-up,
     * docs/04-orchestrierung.md Abschnitt 3). Decides how a `Completed.Authenticated` outcome is
     * read: the outcome's own account, or only the already-bound one.
     */
    val accountAlreadyKnown: Boolean

    data class SelectMethod(
        override val offer: Offer,
        override val accountAlreadyKnown: Boolean
    ) : WebSelectMethodState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
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
}
