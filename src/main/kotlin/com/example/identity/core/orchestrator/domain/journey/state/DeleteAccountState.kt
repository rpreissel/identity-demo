package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * States of DELETE_ACCOUNT (docs/journeys/delete-account.md). [ConfirmPending] always comes first;
 * level and fresh proof follow as the `FRESH_PROOF` sub-journey ([FreshProofState]), never skipped
 * because the channel already has loa2: a hijacked session must not delete the account on its own say-so.
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
}
