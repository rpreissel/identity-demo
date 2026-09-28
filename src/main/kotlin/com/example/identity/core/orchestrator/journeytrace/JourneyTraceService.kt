package com.example.identity.core.orchestrator.journeytrace

import com.example.identity.core.orchestrator.domain.AuthIntent
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import io.swagger.v3.oas.annotations.media.Schema

data class JourneyTraceEntryView(
    val channelSessionId: UUID,
    /** APP or KEYCLOAK - makes the originating facade visible in the log UI. */
    val channelType: String?,
    /** Null until the channel resolves an account - see [JourneyTraceEntry.accountId]. */
    val accountId: Long?,
    /** Null for a channel-level event with no journey of its own (see [JourneyTraceService.recordForChannel]). */
    val journeyId: UUID?,
    /** Set when [journeyId] ran as another journey's precondition; the UI nests it under that parent. */
    val parentJourneyId: UUID?,
    val intent: String?,
    val eventType: String,
    /** The JourneyState subtype the journey was in when this event happened (e.g. "AwaitingTan") - null for a channel-level event. */
    val journeyState: String?,
    /**
     * Whatever the event had to say: strings, numbers, nested lists. `additionalProperties: true`,
     * because springdoc's default would declare every value an object.
     */
    @field:Schema(additionalProperties = Schema.AdditionalPropertiesValue.TRUE)
    val detail: Map<String, Any?>,
    val createdAt: Instant
)

data class JourneyTraceResponse(val entries: List<JourneyTraceEntryView>)

/**
 * What the log records about the channel an entry belongs to. A value, not the `ChannelSession`
 * entity, so `journeytrace` does not depend on `session`, which depends on it.
 */
data class LoggedChannel(
    val channelSessionId: UUID,
    val bindingKeyRef: String?,
    val channelType: String?,
    val accountId: Long?
)

/** The journey side of the same split - see [LoggedChannel]. */
data class LoggedJourney(
    val journeyId: UUID,
    val parentJourneyId: UUID?,
    val intent: AuthIntent
)

@Service
class JourneyTraceService(
    private val journeyTraceRepository: JourneyTraceRepository
) {

    /** [journeyState] is a first-class field, like [eventType] - not just another entry in [detail]. */
    fun record(
        channel: LoggedChannel,
        journey: LoggedJourney,
        eventType: String,
        journeyState: String? = null,
        detail: Map<String, Any?> = emptyMap()
    ) {
        journeyTraceRepository.save(
            JourneyTraceEntry(
                bindingKeyRef = channel.bindingKeyRef,
                channelType = channel.channelType,
                accountId = channel.accountId,
                channelSessionId = channel.channelSessionId,
                journeyId = journey.journeyId,
                parentJourneyId = journey.parentJourneyId,
                intent = journey.intent,
                eventType = eventType,
                journeyState = journeyState,
                detail = detail
            )
        )
    }

    /** For an event outside any journey, e.g. logging out of a channel with nothing running. */
    fun recordForChannel(channel: LoggedChannel, eventType: String, detail: Map<String, Any?> = emptyMap()) {
        journeyTraceRepository.save(
            JourneyTraceEntry(
                bindingKeyRef = channel.bindingKeyRef,
                channelType = channel.channelType,
                accountId = channel.accountId,
                channelSessionId = channel.channelSessionId,
                journeyId = null,
                parentJourneyId = null,
                intent = null,
                eventType = eventType,
                journeyState = null,
                detail = detail
            )
        )
    }

    /**
     * The newest [limit] entries across all channels, for the operator view behind the admin login.
     * An entry logged before its channel resolved an account inherits the `accountId` of a later
     * entry of the same channel, so the whole journey is attributed to its person.
     */
    fun getRecent(limit: Int): JourneyTraceResponse {
        val entries = journeyTraceRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit))
        val accountByChannel = entries
            .filter { it.accountId != null }
            .associate { checkNotNull(it.channelSessionId) to checkNotNull(it.accountId) }
        return JourneyTraceResponse(entries.map { entry ->
            entry.toView().let { view -> view.copy(accountId = view.accountId ?: accountByChannel[view.channelSessionId]) }
        })
    }

    private fun JourneyTraceEntry.toView() = JourneyTraceEntryView(
        channelSessionId = checkNotNull(channelSessionId),
        channelType = channelType,
        accountId = accountId,
        journeyId = journeyId,
        parentJourneyId = parentJourneyId,
        intent = intent?.name,
        eventType = checkNotNull(eventType),
        journeyState = journeyState,
        detail = detail.orEmpty(),
        createdAt = checkNotNull(createdAt)
    )
}
