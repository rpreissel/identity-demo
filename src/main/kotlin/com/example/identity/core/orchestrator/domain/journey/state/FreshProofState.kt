package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.contract.tool_api.claims.AcrLevel

/**
 * The sub-journey behind every self-service action on a signed-in channel
 * (docs/journeys/fresh-proof.md, ADR-61): the session must clear [requiredAcr], and its latest
 * proof must be recent, else one active factor is re-proven. Reached only through `FreshProofJourney.before`.
 * The caller's [purpose] words the screens; what the proof then authorizes is the caller's.
 */
sealed interface FreshProofState : JourneyState {
    val requiredAcr: AcrLevel

    val purpose: ProofPurpose

    /** The check itself, and the state parked while the step-up it required runs. */
    data class Start(
        override val requiredAcr: AcrLevel,
        override val purpose: ProofPurpose,
    ) : FreshProofState, ToolFreeState

    /**
     * Re-prove any one active factor, fresh
     * ([com.example.identity.core.orchestrator.domain.journey.reconfirmationCandidates]).
     * The level alone is not enough: a hijacked session must not act on old evidence.
     */
    data class Reproving(
        override val requiredAcr: AcrLevel,
        override val purpose: ProofPurpose,
        override val offer: Offer,
    ) : FreshProofState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        // Authorizes the caller's one action only, so it never becomes the session's evidence.
        override fun booking(tool: Tool, outcome: ToolOutcome.Completed): Action =
            if (outcome is ToolOutcome.Completed.Authenticated) Action.Reconfirm(tool, outcome)
            else error("${tool.toolId} is not offered by Reproving")
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        // selectionContext names the kind of offer, not the journey.
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = when (purpose) {
            ProofPurpose.DELETE_ACCOUNT -> Text("Konto löschen – Anmeldeverfahren bestätigen")
            ProofPurpose.MANAGE_METHODS -> Text("Anmeldeverfahren verwalten – Anmeldeverfahren bestätigen")
            ProofPurpose.PEER_LOGIN -> Text("Web-Login bestätigen – Anmeldeverfahren bestätigen")
        }
        override val selectionDescription: Text get() = when (purpose) {
            ProofPurpose.DELETE_ACCOUNT -> Text("Bevor Ihr Konto gelöscht wird, bestätigen Sie bitte noch einmal eines Ihrer Anmeldeverfahren.")
            ProofPurpose.MANAGE_METHODS -> Text("Ihr letzter Nachweis liegt länger zurück. Bitte bestätigen Sie zuerst eines Ihrer Anmeldeverfahren.")
            ProofPurpose.PEER_LOGIN -> Text("Bevor Sie den Web-Login bestätigen, bestätigen Sie bitte noch einmal eines Ihrer Anmeldeverfahren.")
        }
    }
}
