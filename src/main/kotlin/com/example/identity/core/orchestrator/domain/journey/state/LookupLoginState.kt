package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * States of LOOKUP_LOGIN (docs/journeys/lookup-login.md). There is no `Identifying`: without a known
 * account, identification would create or adopt one, which is not a login. Once the account is
 * known, a fresh identification runs as the `RE_IDENTIFY` sub-journey, which only confirms that
 * account.
 */
sealed interface LookupLoginState : JourneyState {

    data object Start : LookupLoginState, ToolFreeState {
        override val selectionContext: String get() = "auth"
    }

    data class Credential(
        override val offer: Offer
    ) : LookupLoginState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
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
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Zusätzlicher Faktor erforderlich")
        override val selectionDescription: Text get() = Text("Ihre bisherige Anmeldung reicht für das geforderte Sicherheitsniveau nicht aus. Bitte bestätigen Sie einen weiteren Faktor.")
    }

    /**
     * Explicit and optional: "recognize this device for future logins?". A durable device link must
     * not arise as a side effect of a login the user chose because it has no device binding.
     * Carries no account id: `Action.LinkDevice` reads the account from the live session.
     */
    data object OfferBinding : LookupLoginState, AnswerableState {
        override val question: Question get() = Question.Confirm(
            title = Text("Dieses Gerät merken?"),
            description = Text("Wenn Sie zustimmen, erkennt der Dienst dieses Gerät beim nächsten Mal wieder und Sie müssen Ihre E-Mail-Adresse nicht erneut eingeben. Sie können auch ohne Verknüpfung fortfahren – dann melden Sie sich künftig wieder über E-Mail und Passwort an."),
            confirmLabel = Text("Gerät merken"),
            cancelLabel = Text("Ohne Verknüpfung fortfahren")
        )
    }

    /**
     * A lookup login resolved a different account than the one this device is bound to. Accepting
     * overwrites that durable link, so the prompt says so; declining still logs in without rebinding.
     * Carries no account id: `Action.LinkDevice` reads the account from the live session.
     */
    data object ConfirmDeviceRebind : LookupLoginState, AnswerableState {
        override val question: Question get() = Question.Confirm(
            title = Text("Dieses Gerät ist bereits einem anderen Konto zugeordnet"),
            description = Text("Wenn Sie fortfahren, wird dieses Gerät künftig nur noch diesem Konto zugeordnet. Das bisher verbundene Konto muss sich beim nächsten Mal auf diesem Gerät erneut identifizieren."),
            confirmLabel = Text("Gerät neu zuordnen"),
            cancelLabel = Text("Ohne Verknüpfung fortfahren"),
            destructive = true
        )
    }
}
