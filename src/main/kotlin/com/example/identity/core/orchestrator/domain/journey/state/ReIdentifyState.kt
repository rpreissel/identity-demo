package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel

/**
 * The sub-journey that offers a fresh identification once no active method can reach [targetAcr]
 * (docs/journeys/re-identify.md). Always behind [OfferReIdent]'s explicit confirmation, since it
 * is heavier than picking another factor. It confirms the known account, never adopts another.
 */
sealed interface ReIdentifyState : JourneyState {
    /** The goal this sub-journey was started for, not the channel's durable floor. */
    val targetAcr: AcrLevel

    /** Why identification is offered; carried from [OfferReIdent] into [Identifying], it picks the wording. */
    val kind: Kind

    enum class Kind {
        /** Nothing else reaches [targetAcr]: re-identify instead ("erneut", "nicht erreichbar"). */
        RECOVERY,

        /** A never-identified account offered identification as an optional extra; declining skips it. */
        OPTIONAL,
    }

    data class OfferReIdent(
        override val targetAcr: AcrLevel,
        override val kind: Kind
    ) : ReIdentifyState, AnswerableState {
        override val question: Question
            get() = when (kind) {
                // The account is already set up here (ADR-46): declining skips, it discards nothing.
                Kind.OPTIONAL -> Question.Confirm(
                    title = Text("Identifizieren?"),
                    description = Text("Ihr Konto ist eingerichtet. Optional können Sie sich jetzt zusätzlich identifizieren."),
                    confirmLabel = Text("Identifizieren"),
                    cancelLabel = Text("Ohne Identifizierung weiter")
                )
                Kind.RECOVERY -> Question.Confirm(
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
        override val offer: Offer,
        override val kind: Kind
    ) : ReIdentifyState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = when (kind) {
            Kind.OPTIONAL -> Text("Identifikation (optional)")
            Kind.RECOVERY -> Text("Erneute Identifikation erforderlich")
        }
        override val selectionDescription: Text get() = when (kind) {
            Kind.OPTIONAL -> Text("Wählen Sie ein Verfahren, um sich zu identifizieren.")
            Kind.RECOVERY -> Text("Ihre bestehenden Anmeldeverfahren reichen für das geforderte Sicherheitsniveau nicht aus. Bitte identifizieren Sie sich erneut.")
        }
    }
}
