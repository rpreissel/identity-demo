package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.ToolId

/**
 * Shared by [FastAccessState] and [RegisterState], because both journeys ask the same question
 * here: "does the account already have something that closes the gap?" Each intent keeps its own
 * strategy; they only land on the same value.
 */
data class AuthChoice(
    override val offer: Offer
) : FastAccessState, RegisterState, OfferingState {
    override fun withOffer(offer: Offer) = copy(offer = offer)
    override val selectionContext: String get() = "auth"
    override val selectionTitle: Text get() = Text("Anmeldung – Verfahren wählen")
    override val selectionDescription: Text get() = Text("Für Ihr Konto sind mehrere Anmeldeverfahren hinterlegt. Wählen Sie aus, wie Sie sich anmelden möchten.")
}

/**
 * "Does the account need a new method enrolled?", shared like [AuthChoice]. [emailObligation]
 * records that this run created or adopted an account ([RegisterState.Identifying]); only then does
 * [RegisterState.ConfirmingEmail] follow, since a password needs an identifier and must not stay
 * unreachable. It is checked after enrolment, so the user still chooses which method to set up.
 */
data class Enrolling(
    override val offer: Offer,
    val emailObligation: Boolean = false
) : FastAccessState, RegisterState, OfferingState {
    override fun withOffer(offer: Offer) = copy(offer = offer)
    override val selectionContext: String get() = "enrollment"
    override val selectionTitle: Text get() = Text("Anmeldeverfahren einrichten")
    override val selectionDescription: Text get() = Text("Damit Sie sich beim nächsten Mal schneller anmelden können, richten Sie jetzt ein Verfahren ein.")
}
