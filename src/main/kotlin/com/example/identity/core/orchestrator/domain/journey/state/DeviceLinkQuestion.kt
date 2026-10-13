package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * "May this device recognize the account?", asked once a login or an identification has settled on
 * an account (docs/04-orchestrierung.md #2 "Zweitaccount", docs/09-dpop.md). A durable device link
 * never arises as a side effect and is never overwritten silently, so the question is a state of
 * its own, shared by [LookupLoginState] and [RegisterState]. Only the [wording] is here; what
 * accepting and declining lead to is each strategy's (`deviceLinkAnswered`). Carries no account
 * id: `Action.LinkDevice` reads the account from the live session.
 *
 * Not a [RegisterEnrollFirstState]: `RegisterDispatchStrategy` tells the two REGISTER variants
 * apart by state type, so [RegisterEnrollFirstState.EnrollFirstConfirmDeviceRebind] stays a state
 * of its own, with the [Wording.REBIND] question.
 */
data class DeviceLinkQuestion(val wording: Wording) : LookupLoginState, RegisterState, AnswerableState {

    enum class Wording {
        /** The device is linked to nobody: an optional offer, declining changes nothing. */
        OFFER,

        /** The device is linked to another account: accepting moves the link, declining keeps it and the login. */
        REBIND,

        /** As [REBIND], but declining gives up the whole run: a registration offers no method on a device that stays someone else's. */
        REBIND_OR_CANCEL,
    }

    override val question: Question get() = when (wording) {
        Wording.OFFER -> Question.Confirm(
            title = Text("Dieses Gerät merken?"),
            description = Text("Wenn Sie zustimmen, erkennt der Dienst dieses Gerät beim nächsten Mal wieder und Sie müssen Ihre E-Mail-Adresse nicht erneut eingeben. Sie können auch ohne Verknüpfung fortfahren – dann melden Sie sich künftig wieder über E-Mail und Passwort an."),
            confirmLabel = Text("Gerät merken"),
            cancelLabel = Text("Ohne Verknüpfung fortfahren")
        )
        Wording.REBIND -> rebind(cancelLabel = Text("Ohne Verknüpfung fortfahren"))
        Wording.REBIND_OR_CANCEL -> rebind(cancelLabel = Text("Abbrechen"))
    }

    /** The warning before a link is moved: the same words wherever it is asked, only the way out differs. */
    private fun rebind(cancelLabel: Text) = Question.Confirm(
        title = Text("Dieses Gerät ist bereits einem anderen Konto zugeordnet"),
        description = Text("Wenn Sie fortfahren, wird dieses Gerät künftig nur noch diesem Konto zugeordnet. Das bisher verbundene Konto muss sich beim nächsten Mal auf diesem Gerät erneut identifizieren."),
        confirmLabel = Text("Gerät neu zuordnen"),
        cancelLabel = cancelLabel,
        destructive = true
    )
}
