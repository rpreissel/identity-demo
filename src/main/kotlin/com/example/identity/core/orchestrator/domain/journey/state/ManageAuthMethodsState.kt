package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.ToolId

/**
 * The only intent without a policy goal: one successful enrollment ends it, regardless of the level
 * reached, since the channel was already AUTHENTICATED (docs/journeys/manage-auth-methods.md).
 */
sealed interface ManageAuthMethodsState : JourneyState {

    /**
     * The user's wish before the loa2 gate, and the state the journey is parked in while a step-up
     * runs. So the wish survives the detour: after proving loa2 the user need not act again.
     * [com.example.identity.core.orchestrator.domain.journey.JourneyLifecycle.SUSPENDED] says it is waiting.
     */
    data object AddRequested : ManageAuthMethodsState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    data class RemoveRequested(val methodInstanceId: String) : ManageAuthMethodsState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
    }

    /**
     * The wish to withdraw an account attribute (a confirmed address), gated like [RemoveRequested]:
     * it is destructive self-service too and can take credentials with it.
     */
    data class RetractAttributeRequested(val attributeType: AttributeType) : ManageAuthMethodsState {
        override fun withActive(active: ToolRef?): JourneyState = this
        override fun activatable(availableTools: Set<ToolId>): Set<ToolId> = emptySet()
        override val active: ToolRef? get() = null
        override val selectionContext: String get() = "enrollment"
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
