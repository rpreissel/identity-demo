package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * States of DELETE_ACCOUNT (docs/journeys/delete-account.md). [ConfirmPending] always comes first.
 * The re-proof in [ConfirmationRequired] is not skipped because the channel already has loa2: a
 * hijacked session must not delete the account on its own say-so. Only a recent proof counts as
 * fresh.
 */
sealed interface DeleteAccountState : JourneyState {

    /** "Do you really want to delete your account?", before anything else is checked. */
    data object ConfirmPending : DeleteAccountState, AnswerableState {
        override val question: Question get() = Question.Confirm(
            title = Text("Konto wirklich löschen?"),
            description = Text("Diese Aktion kann nicht rückgängig gemacht werden. Alle Ihre Anmeldemethoden und Kontodaten werden endgültig gelöscht."),
            confirmLabel = Text("Konto löschen"),
            cancelLabel = Text("Abbrechen"),
            destructive = true
        )
    }

    /**
     * Re-prove any one active factor, fresh
     * ([com.example.identity.core.orchestrator.domain.journey.CandidateTools.forReconfirmation]).
     */
    data class ConfirmationRequired(
        override val offer: Offer
    ) : DeleteAccountState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        // selectionContext names the kind of offer, not the intent.
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Konto löschen – Anmeldeverfahren bestätigen")
        override val selectionDescription: Text? get() = Text("Bevor Ihr Konto gelöscht wird, bestätigen Sie bitte noch einmal eines Ihrer Anmeldeverfahren.")
    }
}
