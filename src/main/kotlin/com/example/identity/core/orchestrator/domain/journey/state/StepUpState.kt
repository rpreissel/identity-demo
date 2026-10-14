package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel

sealed interface StepUpState : JourneyState {
    /** What the run is for; both states carry it unchanged from start to finish. */
    val goal: Goal

    /**
     * From [startingAcr] to [targetAcr], usually written `startingAcr upTo targetAcr`. [targetAcr] is
     * the goal of this run, distinct from the channel's durable `acrFloor`.
     * [allowReIdentification]: whether a dead end may fall back to offering `RE_IDENTIFY`. False
     * for CONFIRM_PEER_LOGIN's gate: a peer approval must never let someone acquire a fresh identity
     * just to confirm someone else's login. True by default, because re-identification is an
     * equally valid path to loa2 (docs/04-orchestrierung.md #4), not a lesser fallback.
     * [reason] lets a caller say why this run exists; `null` keeps the generic wording. A [Reason],
     * not text, because the state is persisted and wording must change without touching stored
     * journeys.
     */
    data class Goal(
        val startingAcr: AcrLevel,
        val targetAcr: AcrLevel,
        val allowReIdentification: Boolean = true,
        val reason: Reason? = null
    )

    companion object {
        /**
         * The seed for [com.example.identity.core.orchestrator.domain.journey.Transition.RequireSubJourney]
         * when STEP_UP runs as a precondition (docs/04-orchestrierung.md #7).
         */
        fun forSubJourney(goal: Goal): StepUpState = Start(goal)
    }

    /** Why a caller needs this step-up, when it has more to say than the generic wording. */
    enum class Reason {
        /** CONFIRM_PEER_LOGIN's gate: a browser asks to log in, this session must first prove itself. */
        PEER_LOGIN
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
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Erhöhte Sicherheit erforderlich")
        override val selectionDescription: Text get() {
            val base = when (goal.reason) {
                // The first screen a peer approval without loa2 sees, so it carries the whole
                // context, including "Abbrechen" as the way out.
                Reason.PEER_LOGIN -> Text(
                    "Ein Browser möchte sich mit Ihrem Konto anmelden. Um das zu bestätigen, muss " +
                        "diese Sitzung zunächst selbst ein höheres Sicherheitsniveau nachweisen. Brechen Sie ab, wenn Sie " +
                        "diesen Login nicht selbst ausgelöst haben."
                )
                null -> Text("Die angeforderte Aktion erfordert ein höheres Sicherheitsniveau. Bitte bestätigen Sie Ihre Identität mit einem weiteren Verfahren.")
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
