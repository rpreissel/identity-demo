package com.example.identity.core.orchestrator.journeytrace

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * How long the trace is kept (RetentionJob). The highest-volume table, one row per journey step.
 * It is a debugging trace, not the change log (`account.change_log`, ADR-39), so it need not
 * outlive the channel sessions. Also the window the operator's search reaches back.
 */
val JOURNEY_TRACE_RETENTION: Duration = Duration.ofDays(14)

/**
 * How a journey ended, read from its last trace entry. The trace is kept 14 days, the journey row
 * much shorter, so the trace has to answer this itself.
 */
enum class JourneyTraceOutcome {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    LOGGED_OUT,

    /** Still running, waiting on a sub-journey, or left behind until it expired. */
    OPEN;

    companion object {
        fun of(last: JourneyTraceEntryView): JourneyTraceOutcome {
            val decision = last.detail["decision"]
            return when {
                last.eventType == JourneyTraceEvent.JOURNEY_CANCELLED -> CANCELLED
                last.eventType == JourneyTraceEvent.LOGGED_OUT || decision == "Logout" -> LOGGED_OUT
                decision == "Authenticated" -> SUCCEEDED
                decision == "Abort" -> FAILED
                // The attempt budget spans the journey; its last failure ends it (JourneyService.chargeAttempt).
                last.eventType == JourneyTraceEvent.TOOL_FAILED && (last.detail["attemptBudgetLeft"] as? Number)?.toInt()?.let { it <= 0 } == true -> FAILED
                else -> OPEN
            }
        }
    }
}

/** One journey as a line of the operator's search result. */
data class JourneyTraceSummary(
    val journeyId: JourneyId,
    /** Set when this journey ran as another's precondition (docs/04-orchestrierung.md #7). */
    val parentJourneyId: JourneyId?,
    val channelSessionId: ChannelSessionId,
    val channelType: String?,
    val accountId: AccountId?,
    /** The register's display name, filled in by the admin view (`PersonDirectory.displayName`). */
    val displayName: String? = null,
    val intent: String,
    val startedAt: Instant,
    val lastActivityAt: Instant,
    val stepCount: Int,
    val failedAttempts: Int,
    val outcome: JourneyTraceOutcome
) {
    companion object {
        /** [steps] oldest first, [channelAccount] the account the channel resolved later, if any. */
        fun of(steps: List<JourneyTraceEntryView>, channelAccount: AccountId?): JourneyTraceSummary {
            val first = steps.first()
            return JourneyTraceSummary(
                journeyId = checkNotNull(first.journeyId),
                parentJourneyId = first.parentJourneyId,
                channelSessionId = first.channelSessionId,
                channelType = first.channelType,
                accountId = steps.firstNotNullOfOrNull { it.accountId } ?: channelAccount,
                intent = checkNotNull(first.intent),
                startedAt = first.createdAt,
                lastActivityAt = steps.last().createdAt,
                stepCount = steps.size,
                failedAttempts = steps.count { it.eventType == JourneyTraceEvent.TOOL_FAILED },
                outcome = JourneyTraceOutcome.of(steps.last())
            )
        }
    }
}

/**
 * What the operator searches for. Account, channel or journey anchor the search to a few rows;
 * without an anchor the search pages through every journey start in the time window.
 */
data class JourneyTraceQuery(
    val from: Instant,
    val to: Instant,
    val limit: Int,
    val accountId: AccountId? = null,
    val channelSessionId: ChannelSessionId? = null,
    val journeyId: JourneyId? = null,
    /** A journey name (`Journey.name`). */
    val intent: String? = null,
    val outcome: JourneyTraceOutcome? = null,
    val cursor: JourneyTraceCursor? = null
)

/** Where the next page starts: the last journey start a page looked at. Opaque to the client. */
data class JourneyTraceCursor(val createdAt: Instant, val logId: UUID) {
    fun encode(): String = "${createdAt}_$logId"

    companion object {
        /** Null for anything that is not a cursor this service wrote. */
        fun decode(value: String): JourneyTraceCursor? = runCatching {
            val (at, id) = value.split('_', limit = 2)
            JourneyTraceCursor(Instant.parse(at), UUID.fromString(id))
        }.getOrNull()
    }
}

/**
 * One page of the search. [searchedUntil] is the oldest start this page looked at: a journey or
 * outcome filter is applied after reading, so a page may find fewer than asked, and the operator
 * sees how far back it got. [nextCursor] is null when nothing older is left.
 */
data class JourneyTracePage(
    val journeys: List<JourneyTraceSummary>,
    val nextCursor: String?,
    val searchedUntil: Instant?
)

/** Every entry of one channel, oldest first, its journeys and sub-journeys and its journey-less events. */
data class ChannelSessionTrace(
    val channelSessionId: ChannelSessionId,
    val channelType: String?,
    val accountId: AccountId?,
    /** The register's display name, filled in by the admin view. */
    val displayName: String? = null,
    val entries: List<JourneyTraceEntryView>,
    /** One summary per journey in [entries], so the view reads outcome and duration from the same rule as the search. */
    val journeys: List<JourneyTraceSummary>,
    /** True when the channel has more entries than one answer carries; the oldest are shown. */
    val truncated: Boolean
)
