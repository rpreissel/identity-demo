package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * Shared by [FastAccessState] and [RegisterState], because both journeys ask the same question
 * here: "does the account already have something that closes the gap?" Each journey keeps its own
 * decision; they only land on the same value.
 */
data class AuthChoice(
    override val offer: Offer
) : FastAccessState, RegisterState, OfferingState {
    override fun withOffer(offer: Offer) = copy(offer = offer)
    override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
    override val selectionContext: String get() = "auth"
    override val selectionTitle: Text get() = Text("Anmeldung – Verfahren wählen")
    override val selectionDescription: Text get() = Text("Für Ihr Konto sind mehrere Anmeldeverfahren hinterlegt. Wählen Sie aus, wie Sie sich anmelden möchten.")
}

/**
 * "Does the account need a new method enrolled?", shared like [AuthChoice]. [then] is what REGISTER
 * still owes once the method is in; FAST_ACCESS owes nothing.
 */
data class Enrolling(
    override val offer: Offer,
    val then: AfterEnrolling = AfterEnrolling.NEXT_STEP
) : FastAccessState, RegisterState, OfferingState {
    override fun withOffer(offer: Offer) = copy(offer = offer)
    override val declinePolicy: DeclinePolicy get() = DeclinePolicy.MANDATORY
    override val selectionContext: String get() = "enrollment"
    override val selectionTitle: Text get() = Text("Anmeldeverfahren einrichten")
    override val selectionDescription: Text get() = Text("Damit Sie sich beim nächsten Mal schneller anmelden können, richten Sie jetzt ein Verfahren ein.")
}

/** What follows the enrollment in [Enrolling]. */
enum class AfterEnrolling {
    /** Carry on toward the required level. */
    NEXT_STEP,

    /**
     * This run created or adopted the account ([RegisterState.Identifying]): confirm its address
     * first ([RegisterState.ConfirmingEmail]), since a password needs an identifier and must not stay
     * unreachable. Checked after the enrollment, so the user still chooses which method to set up.
     */
    CONFIRM_EMAIL_FIRST,
}
