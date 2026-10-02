package com.example.identity.tools.auth_qr.internal.authqr

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.tools.auth_qr.AuthQrDescriptor
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
    val saved = slot<AuthQrToolSession>()
    val sessions = mockk<AuthQrToolSessionRepository>().also {
        every { it.save(capture(saved)) } answers { saved.captured }
        every { it.findByToolSessionId(toolSessionId) } returns AuthQrToolSession(toolSessionId = toolSessionId, pairingCode = PAIRING, createdAt = TEST_NOW)
    }
    val browserSide = mockk<QrLoginBrowserSide>()
    val handler = AuthQrToolHandler(AuthQrDescriptor, sessions, browserSide, clock = TEST_CLOCK)

    fun withState(state: QrLoginBrowserSide.State, confirmationCode: String? = null) = apply {
        every { browserSide.advance(PAIRING, confirmationCode) } returns state
    }
}

/**
 * Pure unit test: no Spring context, repositories and the browser side mocked with MockK. Covers
 * how each pairing state becomes an outcome, and the check that the confirming account is the one
 * the channel already knows. Which state a pairing is in is [com.example.identity.tools.auth_qr.internal.QrLoginBrowserSideTest]'s matter.
 */
class AuthQrToolHandlerTest : BehaviorSpec({

    given("a WEB channel that knows account 42") {
        val f = Fixture()
        every { f.browserSide.open(expectedAccountId = AccountId(42L)) } returns PAIRING

        `when`("a step-up for account 42 begins") {
            val outcome = f.handler.start(f.toolSessionId, accountId = AccountId(42L))

            then("it binds the tool session to the pairing it opened for account 42 and shows its code at step waitForApp") {
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

        `when`("the page is reloaded") {
            val outcome = f.handler.read(f.toolSessionId)

            then("it still asks for the confirmation code") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "enterCode", stepData = MissingFields(listOf("confirmationCode")))
            }
        }
    }

    given("a pairing confirmed by account 42, the account it was opened for") {
        val f = Fixture().withState(QrLoginBrowserSide.State.Confirmed(AccountId(42L), expectedAccountId = AccountId(42L)), "123456")

        `when`("the browser sends the confirmation code") {
            val outcome = f.handler.patch(f.toolSessionId, confirmationCode = "123456")

            then("it authenticates at the descriptor's own maxAcr and factorTypes, naming no account") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                    amr = listOf("qr"),
                    achievedAcr = AuthQrDescriptor.maxAcr,
                    factorTypes = AuthQrDescriptor.factorTypes,
                )
            }
        }
    }

    given("a pairing confirmed by account 99, though opened for account 42") {
        val f = Fixture().withState(QrLoginBrowserSide.State.Confirmed(AccountId(99L), expectedAccountId = AccountId(42L)), "123456")

        `when`("the browser sends the confirmation code") {
            val outcome = f.handler.patch(f.toolSessionId, confirmationCode = "123456")

            then("it fails instead of silently switching the account") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Bestätigung passt nicht zu diesem Konto"))
            }
        }
    }

    given("a pairing that failed on the browser side") {
        val f = Fixture().withState(QrLoginBrowserSide.State.Failed(Text("Vom Nutzer abgelehnt")))

        `when`("the browser polls") {
            val outcome = f.handler.patch(f.toolSessionId, confirmationCode = null)

            then("it fails against the known account with the browser side's reason") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Vom Nutzer abgelehnt"))
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
