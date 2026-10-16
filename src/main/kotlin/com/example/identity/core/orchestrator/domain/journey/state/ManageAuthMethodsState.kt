package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.core.orchestrator.domain.journey.Action
import com.example.identity.contract.tool_api.claims.AttributeType

/**
 * The only journey without a policy goal: one successful enrollment ends it, regardless of the level
 * reached, since the channel was already AUTHENTICATED (docs/journeys/manage-auth-methods.md).
 */
sealed interface ManageAuthMethodsState : JourneyState {

    /** What the user asked for; kept while the FRESH_PROOF sub-journey runs. */
    sealed interface Wish : ManageAuthMethodsState, ToolFreeState

    /**
     * The user's wish, and the state the journey is parked in while level and fresh proof are
     * checked (FRESH_PROOF). So the wish survives the detour: after proving, the user need not act again.
     * [com.example.identity.core.orchestrator.domain.journey.JourneyLifecycle.SUSPENDED] says it is waiting.
     */
    data object AddRequested : Wish, ToolFreeState

    data class RemoveRequested(val methodInstanceId: String) : Wish, ToolFreeState

    /**
     * The wish to withdraw an account attribute (a confirmed address), gated like [RemoveRequested]:
     * it is destructive self-service too and can take credentials with it.
     */
    data class RetractAttributeRequested(val attributeType: AttributeType) : Wish, ToolFreeState

    /** The wish to change one credential in place (a new password, a new number), gated like the others. */
    data class ChangeRequested(val methodInstanceId: String) : Wish, ToolFreeState

    /**
     * The one enrollment that replaces [methodInstanceId] is running. Backing out ends the wish;
     * the old credential stays until the new one is adopted, and the new one is never written
     * under a lower level ([Action.AdoptCredential.replaces]).
     */
    data class Changing(
        override val offer: Offer,
        val methodInstanceId: String,
    ) : ManageAuthMethodsState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override fun booking(tool: Tool, outcome: ToolOutcome.Completed): Action =
            if (outcome is ToolOutcome.Completed.Enrolled) Action.AdoptCredential(tool, outcome, replaces = methodInstanceId)
            else error("${tool.toolId} is not offered by Changing")
        // One tool only: backing out ends the change, the old entry stays.
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        override val screen: Screen get() = Screen.SELECT_ENROLLMENT
        override val selectionTitle: Text get() = Text("Anmeldeverfahren ändern")
        override val selectionDescription: Text get() = Text("Sie richten dieses Verfahren neu ein. Der bisherige Eintrag wird mit dem Abschluss ersetzt.")
    }

    data class Enrolling(
        override val offer: Offer
    ) : ManageAuthMethodsState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.MANDATORY
        override val screen: Screen get() = Screen.SELECT_ENROLLMENT
        override val selectionTitle: Text get() = Text("Neues Anmeldeverfahren hinzufügen")
        override val selectionDescription: Text get() = Text("Sie möchten ein weiteres Verfahren einrichten. Wählen Sie aus, welches Sie hinzufügen möchten.")
    }
}
