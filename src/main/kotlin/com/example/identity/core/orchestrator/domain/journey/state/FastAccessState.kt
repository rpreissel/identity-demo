package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.ToolId

/**
 * Into a login on this device as fast as possible, and in a way that works again next time
 * (docs/journeys/fast-access.md). [PreferredAuth] and [AuthChoice] form a fallback chain; once
 * nothing is left, REGISTER runs as a sub-journey, which owns identification and everything it
 * triggers. [AuthChoice] and [Enrolling] are shared with [RegisterState].
 */
sealed interface FastAccessState : JourneyState {

    data object Start : FastAccessState, ToolFreeState

    /** Linked device with a matching device method: an offer of exactly that one, before any choice. */
    data class PreferredAuth(override val offer: Offer) : FastAccessState, OfferingState {
        override fun withOffer(offer: Offer) = copy(offer = offer)
        override val declinePolicy: DeclinePolicy get() = DeclinePolicy.FALLBACK
        override val selectionContext: String get() = "auth"
        override val selectionTitle: Text get() = Text("Anmeldung – Verfahren wählen")
    }
}
