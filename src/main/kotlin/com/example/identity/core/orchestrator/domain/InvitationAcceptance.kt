package com.example.identity.core.orchestrator.domain

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel

/**
 * When a proof that names an invitation may sign in a channel (process access, ADR-48). Checked by
 * `JourneyActionExecutor.acceptInvitation`, the one place an invitation is bound, before it writes
 * anything. Decided by what the proof names, never by which tool delivered it.
 */
object InvitationAcceptance {

    enum class Refusal(val text: Text) {
        /** The App's journeys and tokens know no subject other than an account. */
        NOT_ON_APP(Text("Ein Einmalkennwort gilt nur auf der Website")),

        /** An invitation is complete with its one proof: with no account behind it, nothing can raise its level. */
        BELOW_REQUIRED_LEVEL(Text("Dieses Einmalkennwort genuegt dem verlangten Sicherheitsniveau nicht")),
    }

    /** Null if an invitation proven at [proven] may sign in a [channel] that requires [required]. */
    fun refusal(channel: ChannelType?, proven: AcrLevel, required: AcrLevel): Refusal? = when {
        channel != ChannelType.WEB -> Refusal.NOT_ON_APP
        proven < required -> Refusal.BELOW_REQUIRED_LEVEL
        else -> null
    }
}
