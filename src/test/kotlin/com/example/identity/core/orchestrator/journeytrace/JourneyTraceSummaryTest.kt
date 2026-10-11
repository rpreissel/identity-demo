package com.example.identity.core.orchestrator.journeytrace

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.UUID

class JourneyTraceSummaryTest : BehaviorSpec({

    val channel = ChannelSessionId(UUID.randomUUID())
    val journey = JourneyId(UUID.randomUUID())

    fun step(eventType: String, seconds: Long, detail: Map<String, Any?> = emptyMap(), accountId: AccountId? = null) =
        JourneyTraceEntryView(
            channelSessionId = channel, channelType = "APP", accountId = accountId, journeyId = journey,
            parentJourneyId = null, intent = "FAST_ACCESS", eventType = eventType, journeyState = "Offer",
            detail = detail, createdAt = TEST_NOW.plusSeconds(seconds)
        )

    given("the last entry of a journey") {
        then("its decision or event says how the journey ended") {
            JourneyTraceOutcome.of(step("Completed", 0, mapOf("decision" to "Authenticated"))) shouldBe JourneyTraceOutcome.SUCCEEDED
            JourneyTraceOutcome.of(step("Completed", 0, mapOf("decision" to "Abort"))) shouldBe JourneyTraceOutcome.FAILED
            JourneyTraceOutcome.of(step("CANCELLED", 0)) shouldBe JourneyTraceOutcome.CANCELLED
            JourneyTraceOutcome.of(step("LOGGED_OUT", 0)) shouldBe JourneyTraceOutcome.LOGGED_OUT
        }
        then("a failure with budget left keeps the journey open, the last one of the budget ends it") {
            JourneyTraceOutcome.of(step("TOOL_FAILED", 0, mapOf("attemptBudgetLeft" to 2))) shouldBe JourneyTraceOutcome.OPEN
            JourneyTraceOutcome.of(step("TOOL_FAILED", 0, mapOf("attemptBudgetLeft" to 0))) shouldBe JourneyTraceOutcome.FAILED
        }
        then("a parent waiting on its sub-journey is still open") {
            JourneyTraceOutcome.of(step("Answered", 0, mapOf("decision" to "RequireSubJourney"))) shouldBe JourneyTraceOutcome.OPEN
        }
    }

    given("the steps of a journey, oldest first") {
        val steps = listOf(
            step("Started", 0, mapOf("decision" to "To")),
            step("TOOL_ACTIVATED", 2),
            step("TOOL_FAILED", 5, mapOf("attemptBudgetLeft" to 4)),
            step("Completed", 9, mapOf("decision" to "Authenticated"), accountId = AccountId(7))
        )

        `when`("summarized") {
            val summary = JourneyTraceSummary.of(steps, channelAccount = null)

            then("it counts the steps and failures and spans first to last") {
                summary.stepCount shouldBe 4
                summary.failedAttempts shouldBe 1
                summary.startedAt shouldBe TEST_NOW
                summary.lastActivityAt shouldBe TEST_NOW.plusSeconds(9)
                summary.outcome shouldBe JourneyTraceOutcome.SUCCEEDED
            }
            then("the account a later step resolved belongs to the whole journey") {
                summary.accountId shouldBe AccountId(7)
            }
        }

        `when`("no step carries an account but the channel resolved one later") {
            val summary = JourneyTraceSummary.of(steps.dropLast(1), channelAccount = AccountId(9))

            then("the channel's account is used") {
                summary.accountId shouldBe AccountId(9)
            }
        }
    }

    given("a cursor") {
        val cursor = JourneyTraceCursor(Instant.parse("2026-10-10T09:41:02.123456Z"), UUID.randomUUID())

        then("it reads back what it wrote, and nothing else") {
            JourneyTraceCursor.decode(cursor.encode()) shouldBe cursor
            JourneyTraceCursor.decode("nonsense").shouldBeNull()
            JourneyTraceCursor.decode("2026-10-10T09:41:02Z_not-a-uuid").shouldBeNull()
        }
    }
})
