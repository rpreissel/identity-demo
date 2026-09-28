package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ToolId

/**
 * The sub-journey that offers a fresh identification once no active method can reach [targetAcr]
 * (docs/journeys/re-identify.md). Always behind [OfferReIdent]'s explicit confirmation, since it
 * is heavier than picking another factor. It confirms the known account, never adopts another.
 */
sealed interface ReIdentifyState : JourneyState {
    /** The goal this sub-journey was started for, not the channel's durable floor. */
    val targetAcr: AcrLevel

    /** Carried from [OfferReIdent] into [Identifying]; see [Wording]. */
    val wording: Wording?

    /**
     * Caller-supplied wording. `null` frames the run as a recovery ("re-identify, nothing else
     * reaches the target"). That is wrong for a never-identified account offered identification as
     * an optional extra.
     */
    enum class Wording {
        /** A never-identified account offered identification as an optional extra. */
        OPTIONAL_IDENTIFICATION
    }

    companion object {
        /**
         * The seed for [com.example.identity.core.orchestrator.domain.journey.Transition.RequireSubJourney],
         * so callers never construct [OfferReIdent] themselves.
         */
        fun forSubJourney(targetAcr: AcrLevel, startingAcr: AcrLevel, wording: Wording? = null): ReIdentifyState =
            OfferReIdent(targetAcr, startingAcr, wording)
    }

    /**
     * The channel's acr right before this sub-journey started: `"none"` before login, a real level
     * for an authenticated channel. The cancel path reads it to fall back correctly either way.
     */
    val startingAcr: AcrLevel

    data class OfferReIdent(
        override val targetAcr: AcrLevel,
        override val startingAcr: AcrLevel,
        override val wording: Wording? = null
    ) : ReIdentifyState, AnswerableState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val question: Question
            get() = when (wording) {
                // The account is already set up here (ADR-46): declining skips, it discards nothing.
                Wording.OPTIONAL_IDENTIFICATION -> Question.Confirm(
                    title = Text("Identifizieren?"),
                    description = Text("Ihr Konto ist eingerichtet. Optional können Sie sich jetzt zusätzlich identifizieren."),
                    confirmLabel = Text("Identifizieren"),
                    cancelLabel = Text("Ohne Identifizierung weiter")
                )
                null -> Question.Confirm(
                    title = Text("Erneut identifizieren?"),
                    description = Text(
                        "Mit den vorhandenen Anmeldeverfahren ist das geforderte Sicherheitsniveau " +
                            "nicht erreichbar. Sie können sich stattdessen erneut identifizieren, um es direkt zu erreichen."
                    ),
                    confirmLabel = Text("Erneut identifizieren"),
                    cancelLabel = Text("Abbrechen")
                )
            }
    }

    data class Identifying(
        override val targetAcr: AcrLevel,
        override val startingAcr: AcrLevel,
        override val offer: Offer,
        override val wording: Wording? = null
    ) : ReIdentifyState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = when (wording) {
            Wording.OPTIONAL_IDENTIFICATION -> Text("Identifikation (optional)")
            null -> Text("Erneute Identifikation erforderlich")
        }
        override val selectionDescription: Text get() = when (wording) {
            Wording.OPTIONAL_IDENTIFICATION -> Text("Wählen Sie ein Verfahren, um sich zu identifizieren.")
            null -> Text("Ihre bestehenden Anmeldeverfahren reichen für das geforderte Sicherheitsniveau nicht aus. Bitte identifizieren Sie sich erneut.")
        }
    }
}
