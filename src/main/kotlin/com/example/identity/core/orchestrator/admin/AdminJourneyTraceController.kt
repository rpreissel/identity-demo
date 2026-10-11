package com.example.identity.core.orchestrator.admin

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.InvalidInputException
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.JourneyId
import com.example.identity.core.orchestrator.journeytrace.ChannelSessionTrace
import com.example.identity.core.orchestrator.journeytrace.JOURNEY_TRACE_RETENTION
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceCursor
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceOutcome
import com.example.identity.core.orchestrator.journeytrace.JourneyTracePage
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceQuery
import com.example.identity.core.orchestrator.journeytrace.JourneyTraceService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * The journey trace across every account and device - what the per-device and per-account views
 * (docs/05-api.md) deliberately cannot show. An operator view, so under [ADMIN_API] and its login.
 * It searches instead of listing the newest entries: at 20 million accounts the newest 500 entries
 * are a few seconds of traffic (docs/14-stand-und-weg-zur-produktion.md).
 */
@RestController
@RequestMapping("$ADMIN_API/journey-trace")
@Tag(name = "Admin: journey trace", description = "Journey trace across all accounts and channels")
class AdminJourneyTraceController(
    private val journeyTraceService: JourneyTraceService,
    private val accountService: AccountService,
    private val personDirectory: PersonDirectory,
    private val clock: Clock,
) {

    @GetMapping("/journeys")
    @Operation(
        summary = "Search journeys",
        description = "Journeys newest start first, at most `limit` (1-200). Account, channel or journey narrow the search; " +
            "`from`/`to` default to the 14 days the trace is kept. Intent and outcome filter after reading, so a page may " +
            "hold fewer than `limit`: `searchedUntil` says how far back it got, `nextCursor` continues there."
    )
    fun journeys(
        @RequestParam(required = false) accountId: AccountId?,
        @RequestParam(required = false) channelSessionId: UUID?,
        @RequestParam(required = false) journeyId: UUID?,
        @RequestParam(required = false) intent: AuthIntent?,
        @RequestParam(required = false) outcome: JourneyTraceOutcome?,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): JourneyTracePage {
        val now = clock.instant()
        val page = journeyTraceService.search(
            JourneyTraceQuery(
                from = maxOf(from ?: Instant.MIN, now.minus(JOURNEY_TRACE_RETENTION)),
                to = to ?: now,
                limit = limit.coerceIn(1, MAX_LIMIT),
                accountId = accountId,
                channelSessionId = channelSessionId?.let(::ChannelSessionId),
                journeyId = journeyId?.let(::JourneyId),
                intent = intent,
                outcome = outcome,
                cursor = cursor?.let { JourneyTraceCursor.decode(it) ?: throw InvalidInputException(Text("Ungültiger Cursor")) }
            )
        )
        val names = displayNames(page.journeys.mapNotNull { it.accountId })
        return page.copy(journeys = page.journeys.map { it.copy(displayName = it.accountId?.let(names::get)) })
    }

    @GetMapping("/channel-sessions/{channelSessionId}")
    @Operation(
        summary = "One channel's journey trace",
        description = "Every entry of the channel, oldest first: its journeys, their sub-journeys and its journey-less events."
    )
    fun channelSession(@PathVariable channelSessionId: UUID): ChannelSessionTrace {
        val trace = journeyTraceService.channelSession(ChannelSessionId(channelSessionId))
        return trace.copy(displayName = trace.accountId?.let { displayNames(listOf(it))[it] })
    }

    /** Only the accounts on this page: one register lookup each, never all accounts. */
    private fun displayNames(accountIds: List<AccountId>): Map<AccountId, String?> =
        accountIds.distinct().associateWith { accountId ->
            accountService.findAccount(accountId)?.personId?.let { personDirectory.displayName(it) }
        }

    private companion object {
        const val MAX_LIMIT = 200
    }
}
