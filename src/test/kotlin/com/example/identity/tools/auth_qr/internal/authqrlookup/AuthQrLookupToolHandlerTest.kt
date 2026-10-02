package com.example.identity.tools.auth_qr.internal.authqrlookup

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.tools.auth_qr.api.v1.QrPairingStep
import com.example.identity.tools.auth_qr.internal.QrLoginBrowserSide
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.UUID

private const val PAIRING = "PAIRING1"

/** One tool session on [PAIRING]; the browser side reports whatever state a test sets. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val saved = slot<AuthQrLookupToolSession>()
    val sessions = mockk<AuthQrLookupToolSessionRepository>().also {
        every { it.save(capture(saved)) } answers { saved.captured }
        every { it.findByToolSessionId(toolSessionId) } returns AuthQrLookupToolSession(toolSessionId = toolSessionId, pairingCode = PAIRING, createdAt = TEST_NOW)
    }
    val browserSide = mockk<QrLoginBrowserSide>()
    val handler = AuthQrLookupToolHandler( sessions, browserSide, clock = TEST_CLOCK)

    fun withState(state: QrLoginBrowserSide.State, confirmationCode: String? = null) = apply {
        every { browserSide.advance(PAIRING, confirmationCode) } returns state
    }
}

/**
 * Pure unit test: no Spring context, repositories and the browser side mocked with MockK. Covers
 * how each pairing state becomes an outcome; the account is whichever confirmed, and a failure
 * never names one. Which state a pairing is in is [com.example.identity.tools.auth_qr.internal.QrLoginBrowserSideTest]'s matter.
 */
class AuthQrLookupToolHandlerTest : BehaviorSpec({

    given("a WEB channel that knows no account yet") {
        val f = Fixture()
        every { f.browserSide.open(expectedAccountId = null) } returns PAIRING

        `when`("a passwordless login begins") {
            val outcome = f.handler.start(f.toolSessionId)

            then("it binds the tool session to a pairing that expects no particular account and shows its code") {
                f.saved.captured.pairingCode shouldBe PAIRING
                outcome shouldBe ToolOutcome.InProgress(nextStep = "waitForApp", stepData = QrPairingStep(PAIRING))
            }
        }
    }

    given("a pairing the app has not decided on") {
        val f = Fixture().withState(QrLoginBrowserSide.State.WaitingForApp)

        `when`("the browser polls") {
            val outcome = f.handler.patch(f.toolSessionId, confirmationCode = null)

            then("it keeps showing the pairing code") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "waitForApp", stepData = QrPairingStep(PAIRING))
            }
        }
    }

    given("a pairing the app approved, awaiting the confirmation code") {
        val f = Fixture().withState(QrLoginBrowserSide.State.EnterCode)

        `when`("the browser polls without a code") {
            val outcome = f.handler.patch(f.toolSessionId, confirmationCode = null)

            then("it asks for the confirmation code the app shows") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "enterCode", stepData = MissingFields(listOf("confirmationCode")))
            }
        }
    }

    given("a pairing confirmed by account 99") {
        val f = Fixture().withState(QrLoginBrowserSide.State.Confirmed(AccountId(99L), expectedAccountId = null), "123456")

        `when`("the browser sends the confirmation code") {
            val outcome = f.handler.patch(f.toolSessionId, confirmationCode = "123456")

            then("it authenticates the approving account at its tool's own level and factors") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                    subject = Subject.Account(AccountId(99L)),
                )
            }
        }
    }

    given("a pairing that failed on the browser side after account 99 approved") {
        val failed = QrLoginBrowserSide.State.Failed(Text("Bestätigungscode falsch"))
        val f = Fixture().withState(failed, "000000").withState(failed)

        `when`("the browser sends a wrong confirmation code") {
            val outcome = f.handler.patch(f.toolSessionId, confirmationCode = "000000")

            then("it fails with the browser side's reason, without charging the approving account: the guess was the pairing's code, not its secret") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(Text("Bestätigungscode falsch"), attempted = null)
            }
        }

        `when`("the page is reloaded") {
            val outcome = f.handler.read(f.toolSessionId)

            then("it reads as closed and leaves reporting the outcome to the next PATCH") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "closed")
            }
        }
    }
})
