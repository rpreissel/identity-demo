package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * REGISTER: a fresh identification, even on a linked device (docs/04-orchestrierung.md #2,
 * docs/journeys/register.md). It does not force a second account; the same person still finds the
 * same account. FAST_ACCESS runs it as a sub-journey when it needs a fresh identification.
 * [AuthChoice] and [Enrolling] are shared with [FastAccessState].
 */
sealed interface RegisterState : JourneyState {

    data object Start : RegisterState, ToolFreeState {
        override val selectionContext: String get() = "auth"
    }

    companion object {
        /**
         * The seed for [com.example.identity.core.orchestrator.domain.journey.Transition.RequireSubJourney],
         * so callers never construct [Start] themselves.
         */
        fun forSubJourney(): RegisterState = Start
    }

    /**
     * Identification, for a login FAST_ACCESS could not shortcut and a fresh registration alike.
     * Claim-based identity resolution decides afterwards which one it was.
     */
    data class Identifying(
        override val offer: Offer
    ) : RegisterState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "registration"
        override val selectionStep: String get() = "selectIdentificationMethod"
        override val selectionTitle: Text get() = Text("Identifikation erforderlich")
        override val selectionDescription: Text get() = Text("Bitte identifizieren Sie sich, um Ihr Konto zu finden oder ein neues anzulegen.")
    }

    /**
     * The just-identified account differs from the one this device is linked to
     * (docs/04-orchestrierung.md #2, "Zweitaccount"). Asked right after identification, before any
     * method is offered; the link is never overwritten silently.
     * Carries no account id: `Action.LinkDevice` reads the account from the live session.
     */
    data object ConfirmDeviceRebind : RegisterState, AnswerableState {
        override val question: Question get() = Question.Confirm(
            title = Text("Dieses Gerät ist bereits einem anderen Konto zugeordnet"),
            description = Text("Wenn Sie fortfahren, wird dieses Gerät künftig nur noch diesem Konto zugeordnet. Das bisher verbundene Konto muss sich beim nächsten Mal auf diesem Gerät erneut identifizieren."),
            confirmLabel = Text("Gerät neu zuordnen"),
            cancelLabel = Text("Abbrechen"),
            destructive = true
        )
    }

    /**
     * The correlation step (ADR-18): the attestation established who the subject is, but brought no
     * person reference (an eID card carries no KVNR). Offered before any enrollment, because the
     * outcome decides what kind of account the run builds. Optional: abandoning the tool is the
     * "no", and the run finishes on a prospect account (ADR-10).
     */
    data class Assigning(
        override val offer: Offer
    ) : RegisterState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "registration"
        override val selectionTitle: Text get() = Text("Konto zuordnen")
        override val selectionDescription: Text get() =
            Text("Ihr Konto wird Ihrem Eintrag im Personenverzeichnis zugeordnet - per Versichertennummer oder, ohne sie, per Partnernummer.")
    }

    /**
     * First mandatory step, before any enrollment: a confirmed address is account infrastructure,
     * and `enroll-password` requires it (docs/03-tool-architektur.md #5). Skipped when no attesting
     * tool is available; the obligation is then retried after enrollment.
     */
    data class ConfirmingEmail(
        override val offer: Offer
    ) : RegisterState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("E-Mail-Bestätigung ausstehend")
        override val selectionDescription: Text get() = Text("Ihre E-Mail-Adresse muss noch bestätigt werden - Ihr Konto wird darüber gefunden.")
    }

    /**
     * Third obligation, on every channel (docs/04-orchestrierung.md #5): a REGISTER run must not end
     * below the level its own method management needs (loa2). If the active methods cover only one
     * factor kind, every enrollment adding another kind is offered, e.g. a password or a device
     * binding. It comes after [ConfirmingEmail], because `enroll-password` requires a confirmed email.
     */
    data class SecondFactorKindObligation(
        override val offer: Offer
    ) : RegisterState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("Weiteres Anmeldeverfahren einrichten")
        override val selectionDescription: Text get() = Text("Damit Sie Ihre Anmeldeverfahren später verwalten können, braucht das Konto noch ein Verfahren anderer Art als die schon eingerichteten. Die Liste zeigt, welche hier passen.")
    }
}

