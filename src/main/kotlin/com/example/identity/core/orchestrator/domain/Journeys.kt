package com.example.identity.core.orchestrator.domain

import com.example.identity.core.orchestrator.domain.journey.Journey
import com.example.identity.core.orchestrator.domain.journey.OnlyAsSubJourney
import com.example.identity.core.orchestrator.domain.journey.opensChannelOn
import com.example.identity.core.orchestrator.domain.journeys.ConfirmPeerLoginJourney
import com.example.identity.core.orchestrator.domain.journeys.DeleteAccountJourney
import com.example.identity.core.orchestrator.domain.journeys.FastAccessJourney
import com.example.identity.core.orchestrator.domain.journeys.FreshProofJourney
import com.example.identity.core.orchestrator.domain.journeys.LookupLoginJourney
import com.example.identity.core.orchestrator.domain.journeys.ManageAuthMethodsJourney
import com.example.identity.core.orchestrator.domain.journeys.ReIdentifyJourney
import com.example.identity.core.orchestrator.domain.journeys.RegisterJourney
import com.example.identity.core.orchestrator.domain.journeys.StepUpJourney
import com.example.identity.core.orchestrator.domain.journeys.WebSelectMethodJourney

/**
 * Every kind of journey (docs/04-orchestrierung.md #2). The one place a new kind is added; its
 * start points and its behaviour live in its own object.
 */
object Journeys {
    val all: List<Journey<*>> = listOf(
        FastAccessJourney,
        RegisterJourney,
        LookupLoginJourney,
        WebSelectMethodJourney,
        StepUpJourney,
        ManageAuthMethodsJourney,
        ConfirmPeerLoginJourney,
        DeleteAccountJourney,
        FreshProofJourney,
        ReIdentifyJourney,
    )

    private val byName: Map<String, Journey<*>> = all.associateBy { it.name }

    init {
        require(byName.size == all.size) { "Two journeys share a name: ${all.map { it.name }}" }
        all.forEach { journey ->
            val points = journey.startPoints
            require(points.isNotEmpty()) { "${journey.name} names no start point" }
            require(points.map { it::class }.distinct().size == points.size) { "${journey.name} names a start point twice" }
            require(OnlyAsSubJourney !in points || points.size == 1) { "${journey.name}: OnlyAsSubJourney stands alone" }
        }
    }

    /** The journey a stored name refers to. Names are persisted, so an unknown one is a bug. */
    fun byName(name: String): Journey<*> = byName[name] ?: error("Unknown journey $name")

    /**
     * The journey a client names when opening a [channel]. `null` means the channel's default:
     * FAST_ACCESS for the App, WEB_SELECT_METHOD for Keycloak. Otherwise matches the journey's name
     * case-insensitively (e.g. "register"), so there is no separate wire vocabulary. Returns null
     * for an unknown name or one this channel type cannot be opened with; the caller rejects it.
     */
    fun fromRequest(value: String?, channel: ChannelType): Journey<*>? {
        if (value == null) {
            return when (channel) {
                ChannelType.APP -> FastAccessJourney
                ChannelType.WEB -> WebSelectMethodJourney
            }
        }
        return all.firstOrNull { it.opensChannelOn(channel) && it.name.equals(value, ignoreCase = true) }
    }
}
