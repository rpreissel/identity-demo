package com.example.identity.tools.auth_qr.internal.confirmqrlogin

import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_qr.api.v1.QrPairingStep
import com.example.identity.tools.auth_qr.internal.QrLoginRequest
import com.example.identity.tools.auth_qr.internal.ConfirmationCodeDigest
import com.example.identity.tools.auth_qr.internal.QrLoginRequestRepository
import com.example.identity.tools.auth_qr.internal.QrLoginStatus
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.util.Optional
import java.util.UUID

private const val PAIRING = "ABCD1234"
private val CONFIRMING = AccountId(99L)

/**
 * A approve-qr tool session that already resolved [PAIRING], a pending request opened for
 * [expectedAccountId]; approving and declining succeed unless a test says otherwise.
 */
private class Fixture(expectedAccountId: AccountId?) {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val digest = ConfirmationCodeDigest("test-pepper")
    val sessions = InMemoryToolSessionData().also { it.save(toolSessionId, ConfirmQrLoginToolSession(pairingCode = PAIRING)) }
    val approvedHash = slot<String>()
    val requests = mockk<QrLoginRequestRepository>().also {
        every { it.findById(PAIRING) } returns Optional.of(QrLoginRequest(pairingCode = PAIRING, expectedAccountId = expectedAccountId, createdAt = TEST_NOW))
        every { it.approveIfPending(PAIRING, CONFIRMING, capture(approvedHash), any(), any()) } returns 1
        every { it.denyIfPending(PAIRING, any()) } returns 1
    }
    val handler = ConfirmQrLoginToolHandler(sessions, requests, digest, clock = TEST_CLOCK)

    fun withRequestAlreadyDecided() = apply {
        every { requests.denyIfPending(PAIRING, any()) } returns 0
    }

    /** The request [account] approved earlier: only the code's hash is left in it. */
    fun withRequestApprovedBy(account: AccountId) = apply {
        every { requests.findById(PAIRING) } returns Optional.of(
            QrLoginRequest(pairingCode = PAIRING, expectedAccountId = null, createdAt = TEST_NOW).apply {
                status = QrLoginStatus.APPROVED
                resolvingAccountId = account
                confirmationCodeHash = digest.of("482913")
            }
        )
    }
}

/**
 * Unit test of accept/reject and the expectedAccountId guard: `approve-qr` fails fast in the
 * app when it can never satisfy the web side's `auth-qr` step-up (matching the late check in
 * [com.example.identity.tools.auth_qr.internal.authqr.AuthQrToolHandler]).
 */
class ConfirmQrLoginToolHandlerTest : BehaviorSpec({

    given("a pairing opened for account 42, and account 99 with an active qr enrollment") {
        val f = Fixture(expectedAccountId = AccountId(42L))

        `when`("account 99 accepts") {
            val outcome = f.handler.patch(f.toolSessionId, pairingCode = null, decision = "accept", accountId = CONFIRMING, hasQrEnrollment = true)

            then("it fails immediately") {
                outcome shouldBe ToolOutcome.Failed.NothingGuessed(Text("Bestätigung passt nicht zu diesem Konto"))
            }

            then("it never approves the pairing") {
                verify(exactly = 0) { f.requests.approveIfPending(any(), any(), any(), any(), any()) }
            }
        }
    }

    given("a pairing opened for account 42, and account 99 without any qr enrollment") {
        val f = Fixture(expectedAccountId = AccountId(42L))

        `when`("account 99 accepts") {
            val outcome = f.handler.patch(f.toolSessionId, pairingCode = null, decision = "accept", accountId = CONFIRMING, hasQrEnrollment = false)

            then("the missing enrollment wins over the account mismatch") {
                outcome shouldBe ToolOutcome.Failed.NothingGuessed(Text("QR-Login ist für dieses Konto nicht aktiviert."))
            }
        }
    }

    given("a pairing opened for account 99, the confirming account") {
        val f = Fixture(expectedAccountId = CONFIRMING)

        `when`("account 99 accepts") {
            val outcome = f.handler.patch(f.toolSessionId, pairingCode = null, decision = "accept", accountId = CONFIRMING, hasQrEnrollment = true)

            then("it approves and shows the six-digit code for the browser - it does not finish yet") {
                outcome.shouldShowConfirmationCode(f)
            }

            then("it stores only the code's hash, never the code itself") {
                val code = (outcome as ToolOutcome.InProgress).stepData.shouldBeInstanceOf<QrPairingStep>().confirmationCode.shouldNotBeNull()
                f.approvedHash.captured shouldNotContain code
            }

            then("the browser has two minutes from the approval to type the code") {
                verify(exactly = 1) {
                    f.requests.approveIfPending(PAIRING, CONFIRMING, any(), TEST_NOW, TEST_NOW.plus(Duration.ofMinutes(2)))
                }
            }
        }
    }

    given("a pairing opened without an expected account (auth-qr-lookup, no target account to violate)") {
        val f = Fixture(expectedAccountId = null)

        `when`("account 99 accepts") {
            val outcome = f.handler.patch(f.toolSessionId, pairingCode = null, decision = "accept", accountId = CONFIRMING, hasQrEnrollment = true)

            then("it approves - any account may confirm - and shows the code for the browser") {
                outcome.shouldShowConfirmationCode(f)
            }
        }

        `when`("account 99 declines") {
            val outcome = f.handler.patch(f.toolSessionId, pairingCode = null, decision = "reject", accountId = CONFIRMING, hasQrEnrollment = true)

            then("the pairing is declined and the app run ends without guessing anything") {
                outcome shouldBe ToolOutcome.Failed.NothingGuessed(Text("Vom Nutzer abgelehnt"))
                verify { f.requests.denyIfPending(PAIRING, any()) }
            }
        }
    }

    given("a pairing account 99 approved before the app reloaded") {
        val f = Fixture(expectedAccountId = null).withRequestApprovedBy(CONFIRMING)

        `when`("the app reads the tool again") {
            val outcome = f.handler.read(f.toolSessionId)

            then("it is back on showCode, but the code is gone - only its hash was stored") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "showCode", stepData = MissingFields(listOf("decision")))
            }
        }

        `when`("the app sends a step without a decision") {
            val outcome = f.handler.patch(f.toolSessionId, pairingCode = null, decision = null, accountId = CONFIRMING, hasQrEnrollment = true)

            then("it shows showCode without the code again, and approves nothing a second time") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "showCode", stepData = MissingFields(listOf("decision")))
                verify(exactly = 0) { f.requests.approveIfPending(any(), any(), any(), any(), any()) }
            }
        }
    }

    given("a pairing another device decided on meanwhile") {
        val f = Fixture(expectedAccountId = null).withRequestAlreadyDecided()

        `when`("account 99 declines") {
            val outcome = f.handler.patch(f.toolSessionId, pairingCode = null, decision = "reject", accountId = CONFIRMING, hasQrEnrollment = true)

            then("it says the request was already handled") {
                outcome shouldBe ToolOutcome.Failed.NothingGuessed(Text("Anfrage wurde bereits bearbeitet oder ist abgelaufen"))
            }
        }
    }
})

/** Approval shows a six-digit confirmation code, stored only as its hash; `done` finishes the tool later. */
private fun ToolOutcome.shouldShowConfirmationCode(f: Fixture) {
    val step = this.shouldBeInstanceOf<ToolOutcome.InProgress>()
    step.nextStep shouldBe "showCode"
    val code = step.stepData.shouldBeInstanceOf<QrPairingStep>().confirmationCode.shouldNotBeNull()
    code shouldMatch Regex("\\d{6}")
    f.approvedHash.captured shouldBe f.digest.of(code)
}
