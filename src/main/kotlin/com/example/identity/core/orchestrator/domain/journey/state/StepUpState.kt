package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolId

sealed interface StepUpState : JourneyState {
    /** The goal of this run, distinct from the channel's durable `acrFloor`. */
    val targetAcr: AcrLevel

    companion object {
        /**
         * The seed for [com.example.identity.core.orchestrator.domain.journey.Transition.RequireSubJourney]
         * when STEP_UP runs as a precondition (docs/04-orchestrierung.md #6). [reason] lets the caller
         * say why this run exists; `null` keeps the generic wording. A [Reason], not text, because the
         * state is persisted and wording must change without touching stored journeys.
         */
        fun forSubJourney(targetAcr: AcrLevel, startingAcr: AcrLevel, allowReIdentification: Boolean = true, reason: Reason? = null): StepUpState =
            Start(targetAcr, startingAcr, allowReIdentification, reason)
    }

    /** Why a caller needs this step-up, when it has more to say than the generic wording. */
    enum class Reason {
        /** CONFIRM_PEER_LOGIN's gate: a browser asks to log in, this session must first prove itself. */
        PEER_LOGIN
    }

    data class Start(
        override val targetAcr: AcrLevel,
        val startingAcr: AcrLevel,
        /**
         * Whether a dead end here may fall back to offering `RE_IDENTIFY`. False for
         * CONFIRM_PEER_LOGIN's gate: a peer approval must never let someone acquire a fresh identity
         * just to confirm someone else's login. True by default, because re-identification is an
         * equally valid path to loa2 (docs/04-orchestrierung.md #8), not a lesser fallback.
         */
        val allowReIdentification: Boolean = true,
        /** Carried into [AuthChoice]; see [StepUpState.forSubJourney]. */
        val reason: Reason? = null
    ) : StepUpState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "auth"
    }

    data class AuthChoice(
        override val targetAcr: AcrLevel,
        val startingAcr: AcrLevel,
        override val offer: Offer,
        val allowReIdentification: Boolean = true,
        /** See [StepUpState.forSubJourney]. */
        val reason: Reason? = null,
        /**
         * True once this run already proved one authenticator-axis factor. The text then says that
         * a factor of another kind is needed; two identical screens in a row read as being stuck.
         * Set once when the offer is built.
         */
        val additionalFactorRound: Boolean = false
    ) : StepUpState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Erhöhte Sicherheit erforderlich")
        override val selectionDescription: Text get() {
            val base = when (reason) {
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
