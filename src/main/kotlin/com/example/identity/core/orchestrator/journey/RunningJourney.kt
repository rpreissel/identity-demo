package com.example.identity.core.orchestrator.journey

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import com.example.identity.core.orchestrator.session.id
import com.example.identity.core.orchestrator.domain.journey.JourneyLifecycle
import com.example.identity.core.orchestrator.domain.AuthIntent
import java.time.Instant

/**
 * A journey that was running when it was looked up: the only form in which anyone outside the
 * machine may act on a journey (docs/invarianten.md I-2). [of] refuses a finished, suspended or
 * expired journey, so a replayed tool result has no value to call [JourneyService.applyOutcome]
 * with. It witnesses the lookup, not a live view; callers use it once per request.
 */
class RunningJourney private constructor(internal val entity: AuthJourney) {
    val journeyId: JourneyId get() = entity.id
    val channelSessionId: ChannelSessionId get() = checkNotNull(entity.channelSessionId)
    val intent: AuthIntent get() = entity.requireIntent()

    companion object {
        fun of(journey: AuthJourney, now: Instant): RunningJourney? =
            journey.takeIf { it.lifecycle == JourneyLifecycle.STARTED && !it.isExpiredAt(now) }?.let(::RunningJourney)
    }
}
