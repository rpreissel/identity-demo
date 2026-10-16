package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel

sealed interface StepUpState : JourneyState {
    /** What the run is for; both states carry it unchanged from start to finish. */
    val goal: Goal

    /**
     * From [startingAcr] to [targetAcr], usually written `startingAcr upTo targetAcr`. [targetAcr] is
     * the goal of this run, distinct from the channel's durable `acrFloor`. [purpose] is the action
     * this step-up is the gate of (via FRESH_PROOF); `null` for a plain step-up the channel asks for.
     * It decides the wording and whether a dead end may offer `RE_IDENTIFY`.
     */
    data class Goal(
        val startingAcr: AcrLevel,
        val targetAcr: AcrLevel,
        val purpose: ProofPurpose? = null
    ) {
        val allowsReIdentification: Boolean get() = purpose?.allowsReIdentification ?: true
    }

    data class Start(override val goal: Goal) : StepUpState, ToolFreeState

    data class AuthChoice(
        override val goal: Goal,
        override val offer: Offer,
        /**
         * True once this run already proved one authenticator-axis factor. The text then says that
         * a factor of another kind is needed; two identical screens in a row read as being stuck.
         * Set once when the offer is built.
         */
        val additionalFactorRound: Boolean = false
    ) : StepUpState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        override val screen: Screen get() = Screen.SELECT_AUTH_METHOD
        override val selectionTitle: Text get() = Text("Erhöhte Sicherheit erforderlich")
        override val selectionDescription: Text get() {
            val base = when (goal.purpose) {
                // The first screen a peer approval without loa2 sees, so it carries the whole
                // context, including "Abbrechen" as the way out.
                ProofPurpose.PEER_LOGIN -> Text(
                    "Ein Browser möchte sich mit Ihrem Konto anmelden. Um das zu bestätigen, muss " +
                        "diese Sitzung zunächst selbst ein höheres Sicherheitsniveau nachweisen. Brechen Sie ab, wenn Sie " +
                        "diesen Login nicht selbst ausgelöst haben."
                )
                ProofPurpose.DELETE_ACCOUNT, ProofPurpose.MANAGE_METHODS, null -> Text("Die angeforderte Aktion erfordert ein höheres Sicherheitsniveau. Bitte bestätigen Sie Ihre Identität mit einem weiteren Verfahren.")
            }
            return if (additionalFactorRound) {
                Text(
                    "{anlass} Das eben genutzte Verfahren zählt bereits. Wählen Sie jetzt eines anderer Art, " +
                        "zum Beispiel Passwort statt SMS.",
                    "anlass" to base
                )
            } else {
                base
            }
        }
    }
}

/** A step-up goal from this level to [target]: `AcrLevel.LOA1 upTo AcrLevel.LOA2`. */
infix fun AcrLevel.upTo(target: AcrLevel): StepUpState.Goal = StepUpState.Goal(startingAcr = this, targetAcr = target)
