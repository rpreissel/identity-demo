package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.ToolId

/**
 * The "Enrollment zuerst" variant of REGISTER (docs/journeys/register.md), switched by a feature
 * flag: enroll first, identify optionally later. It shares no states with [RegisterState], so both
 * journeys stay readable on their own. The `EnrollFirst*` prefix keeps `AuthJourney.stateType`
 * (plain `simpleName`) from colliding with [RegisterState] names. The account is created with
 * `personId = null` and bound to a person only through a later `RE_IDENTIFY` sub-journey.
 */
sealed interface RegisterEnrollFirstState : JourneyState {

    /**
     * The journey's beginning and the resume marker for the closing, optional RE_IDENTIFY
     * sub-journey; the arriving event tells them apart. Never rendered with an offer of its own.
     */
    data object EnrollFirstStart : RegisterEnrollFirstState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    /**
     * This device is linked to a different account, so the implicit binding was skipped (never a
     * rebind, see `linksDeviceImplicitly`). Asked at the end, because only then has the optional
     * identification run. Declining finishes without a device link; login still works through the
     * lookup tools.
     */
    data object EnrollFirstConfirmDeviceRebind : RegisterEnrollFirstState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val question: Question get() = Question.Confirm(
            title = Text("Dieses Gerät ist bereits einem anderen Konto zugeordnet"),
            description = Text("Wenn Sie fortfahren, wird dieses Gerät künftig nur noch Ihrem neuen Konto zugeordnet. Das bisher verbundene Konto muss sich beim nächsten Mal auf diesem Gerät erneut identifizieren. Ohne Zuordnung bleibt Ihr neues Konto nutzbar - Sie melden sich dann künftig über E-Mail und Passwort an."),
            confirmLabel = Text("Gerät neu zuordnen"),
            cancelLabel = Text("Ohne Zuordnung fortfahren"),
            destructive = true
        )
    }

    /**
     * Mandatory first step: only tools that attest an email address. Declining re-offers the same
     * set. Skipped only if no such tool is available at all.
     */
    data class EnrollFirstAttestingEmail(
        override val offer: Offer
    ) : RegisterEnrollFirstState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("E-Mail-Adresse bestätigen")
        override val selectionDescription: Text get() = Text("Zuerst wird Ihre E-Mail-Adresse bestätigt - Ihr Konto wird darüber gefunden. SMS folgt danach, die Identifikation ist optional und kommt erst zum Schluss.")
    }

    /** Mandatory second step after [EnrollFirstAttestingEmail], equally non-skippable. */
    data class EnrollFirstEnrollingSms(
        override val offer: Offer
    ) : RegisterEnrollFirstState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("SMS als Anmeldeverfahren einrichten")
        override val selectionDescription: Text get() = Text("Danach wird SMS als zweites Anmeldeverfahren eingerichtet - die Identifikation ist optional und kommt erst zum Schluss.")
    }

    /**
     * Fallback when the ACR floor is still not reached after email and SMS, or when neither was
     * available at the start.
     */
    data class EnrollFirstEnrolling(
        override val offer: Offer
    ) : RegisterEnrollFirstState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("Anmeldeverfahren einrichten")
        override val selectionDescription: Text get() = Text("Richten Sie ein Anmeldeverfahren ein - die Identifikation ist optional und folgt erst danach.")
    }

    data class EnrollFirstConfirmingEmail(
        override val offer: Offer
    ) : RegisterEnrollFirstState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("E-Mail-Bestätigung ausstehend")
        override val selectionDescription: Text get() = Text("Ihre E-Mail-Adresse muss noch bestätigt werden - Ihr Konto wird darüber gefunden.")
    }

    /** As [RegisterState.SecondFactorKindObligation], but reached without identification. */
    data class EnrollFirstSecondFactorKindObligation(
        override val offer: Offer
    ) : RegisterEnrollFirstState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("Weiteres Anmeldeverfahren einrichten")
        override val selectionDescription: Text get() = Text("Damit Sie Ihre Anmeldeverfahren später verwalten können, braucht das Konto noch ein Verfahren anderer Art als die schon eingerichteten. Die Liste zeigt, welche hier passen.")
    }
}
