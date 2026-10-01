package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * The single state of a LOGOUT journey: a confirmation prompt. On accept the channel is logged
 * out; on decline the journey is cancelled and the channel stays AUTHENTICATED.
 */
sealed interface LogoutState : JourneyState {

    data object ConfirmPending : LogoutState, AnswerableState {
        override val question: Question get() = Question.Confirm(
            title = Text("Wirklich abmelden?"),
            description = Text("Ihre aktuelle Sitzung wird beendet. Um erneut zuzugreifen, müssen Sie sich wieder anmelden."),
            confirmLabel = Text("Abmelden"),
            cancelLabel = Text("Abbrechen"),
            destructive = false
        )
    }
}
