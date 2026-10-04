package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AttributeType

/**
 * The only intent without a policy goal: one successful enrollment ends it, regardless of the level
 * reached, since the channel was already AUTHENTICATED (docs/journeys/manage-auth-methods.md).
 */
sealed interface ManageAuthMethodsState : JourneyState {

    /** What the user asked for; kept through a step-up and through [ConfirmationRequired]. */
    sealed interface Wish : ManageAuthMethodsState

    /**
     * The user's wish before the loa2 gate, and the state the journey is parked in while a step-up
     * runs. So the wish survives the detour: after proving loa2 the user need not act again.
     * [com.example.identity.core.orchestrator.domain.journey.JourneyLifecycle.SUSPENDED] says it is waiting.
     */
    data object AddRequested : Wish, ToolFreeState {
        override val selectionContext: String get() = "enrollment"
    }

    data class RemoveRequested(val methodInstanceId: String) : Wish, ToolFreeState {
        override val selectionContext: String get() = "enrollment"
    }

    /**
     * The wish to withdraw an account attribute (a confirmed address), gated like [RemoveRequested]:
     * it is destructive self-service too and can take credentials with it.
     */
    data class RetractAttributeRequested(val attributeType: AttributeType) : Wish, ToolFreeState {
        override val selectionContext: String get() = "enrollment"
    }

    /** The wish to change one credential in place (a new password, a new number), gated like the others. */
    data class ChangeRequested(val methodInstanceId: String) : Wish, ToolFreeState {
        override val selectionContext: String get() = "enrollment"
    }

    /**
     * The one enrollment that replaces [methodInstanceId] is running. Backing out ends the wish;
     * the old credential stays until the new one is adopted.
     */
    data class Changing(
        override val offer: Offer,
        val methodInstanceId: String
    ) : ManageAuthMethodsState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("Anmeldeverfahren ändern")
        override val selectionDescription: Text get() = Text("Sie richten dieses Verfahren neu ein. Der bisherige Eintrag wird mit dem Abschluss ersetzt.")
    }

    /**
     * The session's latest proof is too old for [wish]: re-prove any one active factor first
     * ([com.example.identity.core.orchestrator.domain.journey.CandidateTools.forReconfirmation]).
     */
    data class ConfirmationRequired(
        override val offer: Offer,
        val wish: Wish
    ) : ManageAuthMethodsState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        // selectionContext names the kind of offer, not the intent.
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Anmeldeverfahren verwalten – Anmeldeverfahren bestätigen")
        override val selectionDescription: Text get() = Text("Ihr letzter Nachweis liegt länger zurück. Bitte bestätigen Sie zuerst eines Ihrer Anmeldeverfahren.")
    }

    data class Enrolling(
        override val offer: Offer
    ) : ManageAuthMethodsState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val selectionContext: String get() = "enrollment"
        override val selectionTitle: Text get() = Text("Neues Anmeldeverfahren hinzufügen")
        override val selectionDescription: Text get() = Text("Sie möchten ein weiteres Verfahren einrichten. Wählen Sie aus, welches Sie hinzufügen möchten.")
    }
}
