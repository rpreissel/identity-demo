package com.example.identity.tools.auth_qr.internal.authqrlookup

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.tools.auth_qr.AuthQrLookupDescriptor
import com.example.identity.tools.auth_qr.api.v1.QrPairingStep
import com.example.identity.tools.auth_qr.internal.ConfirmationCodeDigest
import com.example.identity.tools.auth_qr.internal.QrLoginBrowserSide
import com.example.identity.tools.auth_qr.internal.QrLoginRequest
import com.example.identity.tools.auth_qr.internal.QrLoginRequestRepository
import com.example.identity.tools.auth_qr.internal.QrLoginStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK, the browser side real. Covers
 * how each pairing state becomes an outcome; the account is whichever confirmed, and a failure
 * never names one.
 */
class AuthQrLookupToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthQrLookupToolSessionRepository>()
    val requests = mockk<QrLoginRequestRepository>()
    val digest = ConfirmationCodeDigest("test-pepper")
    val handler = AuthQrLookupToolHandler(AuthQrLookupDescriptor, toolDataRepository, QrLoginBrowserSide(requests, digest, clock = TEST_CLOCK), clock = TEST_CLOCK)

    /** A tool session waiting on a pairing without expected account; returns its id. */
    fun sessionOn(
        pairingCode: String,
        status: QrLoginStatus,
        resolvingAccountId: AccountId? = null,
        confirmationAttempts: Int = 0,
    ): ToolSessionId {
        val request = QrLoginRequest(pairingCode = pairingCode, expectedAccountId = null, createdAt = TEST_NOW).apply {
            this.status = status
            this.resolvingAccountId = resolvingAccountId
            this.confirmationAttempts = confirmationAttempts
            expiresAt = TEST_NOW.plusSeconds(60)
        }
        every { requests.findById(pairingCode) } returns Optional.of(request)
        val toolSessionId = ToolSessionId(UUID.randomUUID())
        every { toolDataRepository.findByToolSessionId(toolSessionId) } returns
            AuthQrLookupToolSession(toolSessionId = toolSessionId, pairingCode = pairingCode, createdAt = TEST_NOW)
        return toolSessionId
    }

    given("start()") {
        `when`("a passwordless login begins") {
            val savedRequest = slot<QrLoginRequest>()
            every { requests.save(capture(savedRequest)) } answers { savedRequest.captured }
            val savedSession = slot<AuthQrLookupToolSession>()
            every { toolDataRepository.save(capture(savedSession)) } answers { savedSession.captured }
            val outcome = handler.start(ToolSessionId(UUID.randomUUID()))

            then("it opens a pairing that expects no particular account and shows its code") {
                val pairingCode = savedRequest.captured.pairingCode
                savedRequest.captured.expectedAccountId shouldBe null
                savedSession.captured.pairingCode shouldBe pairingCode
                outcome shouldBe ToolOutcome.InProgress(nextStep = "waitForApp", stepData = QrPairingStep(pairingCode))
            }
        }
    }

    given("patch() after account 99 approved in the app") {
        val toolSessionId = sessionOn("APPROVE1", QrLoginStatus.APPROVED, resolvingAccountId = AccountId(99L))
        every { requests.completeIfConfirmed("APPROVE1", digest.of("123456"), any()) } returns 1
        every { requests.completeIfConfirmed("APPROVE1", digest.of("000000"), any()) } returns 0
        every { requests.countWrongConfirmation("APPROVE1", any()) } returns 1

        `when`("the browser polls without a code") {
            val outcome = handler.patch(toolSessionId, confirmationCode = null)

            then("it asks for the confirmation code the app shows") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "enterCode", stepData = MissingFields(listOf("confirmationCode")))
            }
        }

        `when`("the browser sends a wrong confirmation code") {
            val outcome = handler.patch(toolSessionId, confirmationCode = "000000")

            then("it fails without charging the approving account: the guess was the pairing's code, not its secret") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(Text("Bestätigungscode falsch"), attempted = null)
            }
        }

        `when`("the browser sends the right confirmation code") {
            val outcome = handler.patch(toolSessionId, confirmationCode = "123456")

            then("it authenticates the approving account at the descriptor's own maxAcr and factorTypes") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                    amr = listOf("qr"),
                    achievedAcr = AuthQrLookupDescriptor.maxAcr,
                    factorTypes = AuthQrLookupDescriptor.factorTypes,
                    subject = Subject.Account(AccountId(99L)),
                )
            }
        }
    }

    given("patch() on an approved pairing with its last confirmation attempt left") {
        val toolSessionId = sessionOn("LASTTRY1", QrLoginStatus.APPROVED, resolvingAccountId = AccountId(99L), confirmationAttempts = 2)
        every { requests.completeIfConfirmed("LASTTRY1", any(), any()) } returns 0
        every { requests.countWrongConfirmation("LASTTRY1", any()) } returns 1

        `when`("the browser sends another wrong code") {
            val outcome = handler.patch(toolSessionId, confirmationCode = "000000")

            then("it says the pairing is burned, still naming no account") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(
                    Text("Zu viele falsche Bestätigungscodes. Bitte starten Sie die Anmeldung per QR-Code neu."),
                    attempted = null,
                )
            }
        }
    }

    given("patch() on a pairing another browser already completed") {
        val toolSessionId = sessionOn("REPLAY01", QrLoginStatus.COMPLETED, resolvingAccountId = AccountId(99L))

        `when`("the confirmation code is replayed") {
            val outcome = handler.patch(toolSessionId, confirmationCode = "123456")

            then("it does not log in a second browser") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(Text("Anfrage wurde bereits bearbeitet oder ist abgelaufen"), attempted = null)
            }
        }
    }

    given("patch() after the app declined") {
        val toolSessionId = sessionOn("DENIED01", QrLoginStatus.DENIED)

        `when`("the browser polls") {
            val outcome = handler.patch(toolSessionId, confirmationCode = null)

            then("it fails as declined, naming no account") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(Text("Vom Nutzer abgelehnt"), attempted = null)
            }
        }
    }
})
