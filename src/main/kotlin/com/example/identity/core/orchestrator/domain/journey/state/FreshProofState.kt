package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel

/**
 * The sub-journey behind every self-service action on a signed-in channel
 * (docs/journeys/fresh-proof.md, ADR-61): the session must clear [requiredAcr], and its latest
 * proof must be recent, else one active factor is re-proven. Reached only through [forSubJourney].
 * The caller's [purpose] words the screens; what the proof then authorizes is the caller's.
 */
sealed interface FreshProofState : JourneyState {
    val requiredAcr: AcrLevel

    val purpose: Purpose

    /**
     * Who asks for the proof. A value, not text: the state is persisted, and wording must change
     * without touching stored journeys.
     */
    enum class Purpose(
        /** False where a dead end must not let someone acquire a fresh identity just to pass (CONFIRM_PEER_LOGIN). */
        val allowReIdentification: Boolean = true,
        val stepUpReason: StepUpState.Reason? = null,
    ) {
        GENERIC,
        DELETE_ACCOUNT,
        MANAGE_METHODS,
        PEER_LOGIN(allowReIdentification = false, stepUpReason = StepUpState.Reason.PEER_LOGIN),
    }

    companion object {
        /**
         * The seed for [com.example.identity.core.orchestrator.domain.journey.Transition.RequireSubJourney],
         * so callers never construct [Start] themselves.
         */
        fun forSubJourney(requiredAcr: AcrLevel, purpose: Purpose): FreshProofState = Start(requiredAcr, purpose)
    }

    /** The check itself, and the state parked while the step-up it required runs. */
    data class Start(
        override val requiredAcr: AcrLevel,
        override val purpose: Purpose,
    ) : FreshProofState, ToolFreeState

    /**
     * Re-prove any one active factor, fresh
     * ([com.example.identity.core.orchestrator.domain.journey.CandidateTools.forReconfirmation]).
     * The level alone is not enough: a hijacked session must not act on old evidence.
     */
    data class Reproving(
        override val requiredAcr: AcrLevel,
        override val purpose: Purpose,
        override val offer: Offer,
    ) : FreshProofState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        // selectionContext names the kind of offer, not the intent.
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = when (purpose) {
            Purpose.GENERIC -> Text("Anmeldeverfahren bestätigen")
            Purpose.DELETE_ACCOUNT -> Text("Konto löschen – Anmeldeverfahren bestätigen")
            Purpose.MANAGE_METHODS -> Text("Anmeldeverfahren verwalten – Anmeldeverfahren bestätigen")
            Purpose.PEER_LOGIN -> Text("Web-Login bestätigen – Anmeldeverfahren bestätigen")
        }
        override val selectionDescription: Text get() = when (purpose) {
            Purpose.GENERIC -> Text("Ihr letzter Nachweis liegt länger zurück. Bitte bestätigen Sie noch einmal eines Ihrer Anmeldeverfahren.")
            Purpose.DELETE_ACCOUNT -> Text("Bevor Ihr Konto gelöscht wird, bestätigen Sie bitte noch einmal eines Ihrer Anmeldeverfahren.")
            Purpose.MANAGE_METHODS -> Text("Ihr letzter Nachweis liegt länger zurück. Bitte bestätigen Sie zuerst eines Ihrer Anmeldeverfahren.")
            Purpose.PEER_LOGIN -> Text("Bevor Sie den Web-Login bestätigen, bestätigen Sie bitte noch einmal eines Ihrer Anmeldeverfahren.")
        }
    }
}
