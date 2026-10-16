package com.example.identity.core.orchestrator.journeytrace

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.UUID
import io.swagger.v3.oas.annotations.media.Schema

data class JourneyTraceEntryView(
    val channelSessionId: ChannelSessionId,
    /** APP or WEB - makes the originating facade visible in the log UI. */
    val channelType: String?,
    /** Null until the channel resolves an account - see [JourneyTraceEntry.accountId]. */
    val accountId: AccountId?,
    /** Null for a channel-level event with no journey of its own (see [JourneyTraceService.recordForChannel]). */
    val journeyId: JourneyId?,
    /** Set when [journeyId] ran as another journey's precondition; the UI nests it under that parent. */
    val parentJourneyId: JourneyId?,
    val intent: String?,
    val eventType: JourneyTraceEvent,
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

/**
 * What the log records about the channel an entry belongs to. A value, not the `ChannelSession`
 * entity, so `journeytrace` does not depend on `session`, which depends on it.
 */
data class LoggedChannel(
    val channelSessionId: ChannelSessionId,
    val bindingKeyRef: String?,
    val channelType: String?,
    val accountId: AccountId?
)

/** The journey side of the same split - see [LoggedChannel]. */
data class LoggedJourney(
    val journeyId: JourneyId,
    val parentJourneyId: JourneyId?,
    /** The journey's name (`Journey.name`). */
    val intent: String
)

@Service
class JourneyTraceService(
    private val journeyTraceRepository: JourneyTraceRepository,
    private val clock: Clock
) {

    /** [journeyState] is a first-class field, like [eventType] - not just another entry in [detail]. */
    fun record(
        channel: LoggedChannel,
        journey: LoggedJourney,
        eventType: JourneyTraceEvent,
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
                detail = detail,
                createdAt = clock.instant()
            )
        )
    }

    /** For an event outside any journey, e.g. logging out of a channel with nothing running. */
    fun recordForChannel(channel: LoggedChannel, eventType: JourneyTraceEvent, detail: Map<String, Any?> = emptyMap()) {
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
                detail = detail,
                createdAt = clock.instant()
            )
        )
    }

    /**
     * The operator's search (`AdminJourneyTraceController`): journeys, newest start first. It pages
     * over the journey starts, one entry per journey, then reads the steps of just those journeys,
     * so a page costs the same at 20 million accounts as in the demo. Journey and outcome are only
     * known once read, so they filter afterwards, at most [SCAN_BUDGET] starts per page.
     */
    fun search(query: JourneyTraceQuery): JourneyTracePage {
        query.journeyId?.let { journeyId ->
            val steps = journeyTraceRepository.findByJourneyIdValueIn(listOf(journeyId.value)).map { it.toView() }
            val summaries = summarize(steps).filter { it.matches(query) }
            return JourneyTracePage(summaries, nextCursor = null, searchedUntil = null)
        }
        val channels: List<UUID>? = when {
            query.channelSessionId != null -> listOf(query.channelSessionId.value)
            query.accountId != null -> journeyTraceRepository.findChannelSessionIdValuesOfAccount(query.accountId, query.from)
            else -> null
        }
        if (channels != null && channels.isEmpty()) return JourneyTracePage(emptyList(), nextCursor = null, searchedUntil = null)

        val found = mutableListOf<JourneyTraceSummary>()
        var cursor = query.cursor ?: JourneyTraceCursor(query.to, UUID(0, 0))
        var scanned = 0
        var exhausted = false
        val batchSize = (query.limit * 2).coerceIn(MIN_BATCH, MAX_BATCH)
        while (found.size < query.limit && scanned < SCAN_BUDGET) {
            val page = PageRequest.of(0, batchSize)
            val starts = if (channels == null) {
                journeyTraceRepository.findStartsBefore(query.from, cursor.createdAt, cursor.logId, page)
            } else {
                journeyTraceRepository.findStartsInChannelsBefore(channels, query.from, cursor.createdAt, cursor.logId, page)
            }
            scanned += starts.size
            val byJourney = summarize(starts.filter { query.intent == null || it.intent == query.intent })
                .associateBy { it.journeyId }
            for (start in starts) {
                cursor = JourneyTraceCursor(checkNotNull(start.createdAt), checkNotNull(start.logId))
                byJourney[start.journeyId]?.takeIf { it.matches(query) }?.let(found::add)
                if (found.size == query.limit) break
            }
            if (starts.size < batchSize && found.size < query.limit) {
                exhausted = true
                break
            }
        }
        return JourneyTracePage(
            journeys = found,
            nextCursor = if (exhausted) null else cursor.encode(),
            searchedUntil = if (scanned == 0) null else cursor.createdAt
        )
    }

    /**
     * One channel's entries, oldest first, for the operator's detail view. An entry logged before
     * the channel resolved an account inherits the account of a later one, so the whole channel is
     * attributed to its person.
     */
    fun channelSession(channelSessionId: ChannelSessionId): ChannelSessionTrace {
        val rows = journeyTraceRepository.findByChannelSessionIdOldestFirst(channelSessionId, PageRequest.of(0, MAX_CHANNEL_ENTRIES + 1))
        val entries = rows.take(MAX_CHANNEL_ENTRIES).map { it.toView() }
        val accountId = entries.firstNotNullOfOrNull { it.accountId } ?: accountOfChannel(channelSessionId)
        val attributed = entries.map { it.copy(accountId = it.accountId ?: accountId) }
        return ChannelSessionTrace(
            channelSessionId = channelSessionId,
            channelType = entries.firstNotNullOfOrNull { it.channelType },
            accountId = accountId,
            entries = attributed,
            journeys = attributed.filter { it.journeyId != null }.groupBy { it.journeyId }.values
                .map { JourneyTraceSummary.of(it, accountId) },
            truncated = rows.size > MAX_CHANNEL_ENTRIES
        )
    }

    /** The account a channel resolved, read from any of its entries. */
    private fun accountOfChannel(channelSessionId: ChannelSessionId): AccountId? =
        journeyTraceRepository.findAccountsOfChannelValues(listOf(channelSessionId.value)).firstOrNull()?.let { it[1] as AccountId }

    /** The steps of the journeys [starts] begin, one summary each. */
    @JvmName("summarizeStarts")
    private fun summarize(starts: List<JourneyTraceEntry>): List<JourneyTraceSummary> {
        if (starts.isEmpty()) return emptyList()
        val steps = journeyTraceRepository.findByJourneyIdValueIn(starts.mapNotNull { it.journeyId?.value }).map { it.toView() }
        return summarize(steps)
    }

    /** [steps] oldest first, of any number of journeys. */
    private fun summarize(steps: List<JourneyTraceEntryView>): List<JourneyTraceSummary> {
        if (steps.isEmpty()) return emptyList()
        val channelAccounts = journeyTraceRepository.findAccountsOfChannelValues(steps.map { it.channelSessionId.value }.distinct())
            .associate { (it[0] as UUID) to (it[1] as AccountId) }
        return steps.filter { it.journeyId != null }.groupBy { it.journeyId }.values
            .map { journeySteps -> JourneyTraceSummary.of(journeySteps, channelAccounts[journeySteps.first().channelSessionId.value]) }
    }

    private fun JourneyTraceSummary.matches(query: JourneyTraceQuery) =
        (query.intent == null || intent == query.intent) && (query.outcome == null || outcome == query.outcome)

    private fun JourneyTraceEntry.toView() = JourneyTraceEntryView(
        channelSessionId = checkNotNull(channelSessionId),
        channelType = channelType,
        accountId = accountId,
        journeyId = journeyId,
        parentJourneyId = parentJourneyId,
        intent = intent,
        eventType = checkNotNull(eventType),
        journeyState = journeyState,
        detail = detail.orEmpty(),
        createdAt = checkNotNull(createdAt)
    )

    private companion object {
        /** Journey starts one search page reads at most while a journey or outcome filter skips them. */
        const val SCAN_BUDGET = 2000
        const val MIN_BATCH = 50
        const val MAX_BATCH = 400
        /** Entries one channel answer carries; an app channel lives as long as the app stays open. */
        const val MAX_CHANNEL_ENTRIES = 2000
    }
}
