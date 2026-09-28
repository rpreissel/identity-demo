package com.example.identity.tools.auth_qr.internal.authqr

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.tools.auth_qr.AuthQrDescriptor
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
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK, the browser side real. Covers
 * how each pairing state becomes an outcome, and the check that the confirming account is the one
 * the channel already knows.
 */
class AuthQrToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthQrToolSessionRepository>()
    val requests = mockk<QrLoginRequestRepository>()
    val digest = ConfirmationCodeDigest("test-pepper")
    val handler = AuthQrToolHandler(AuthQrDescriptor, toolDataRepository, QrLoginBrowserSide(requests, digest))

    /** A tool session waiting on a pairing in [status]; returns its id. */
    fun sessionOn(
        pairingCode: String,
        status: QrLoginStatus,
        expectedAccountId: Long = 42L,
        resolvingAccountId: Long? = null,
        expiresAt: Instant = Instant.now().plusSeconds(60),
    ): UUID {
        val request = QrLoginRequest(pairingCode = pairingCode, expectedAccountId = expectedAccountId).apply {
            this.status = status
            this.resolvingAccountId = resolvingAccountId
            this.expiresAt = expiresAt
        }
        every { requests.findById(pairingCode) } returns Optional.of(request)
        val toolSessionId = UUID.randomUUID()
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(AuthQrToolSession(toolSessionId = toolSessionId, pairingCode = pairingCode))
        return toolSessionId
    }

    given("start()") {
        `when`("a step-up for account 42 begins") {
            val savedRequest = slot<QrLoginRequest>()
            every { requests.save(capture(savedRequest)) } answers { savedRequest.captured }
            val savedSession = slot<AuthQrToolSession>()
            every { toolDataRepository.save(capture(savedSession)) } answers { savedSession.captured }
            val outcome = handler.start(UUID.randomUUID(), accountId = 42L)

            then("it opens a pairing that expects account 42 and shows its code at step waitForApp") {
                val pairingCode = savedRequest.captured.pairingCode
                savedRequest.captured.expectedAccountId shouldBe 42L
                savedSession.captured.pairingCode shouldBe pairingCode
                outcome shouldBe ToolOutcome.InProgress(nextStep = "waitForApp", stepData = QrPairingStep(pairingCode))
            }
        }
    }

    given("patch() while the app has not decided") {
        val toolSessionId = sessionOn("PENDING1", QrLoginStatus.PENDING)

        `when`("the browser polls") {
            val outcome = handler.patch(toolSessionId, confirmationCode = null)

            then("it keeps showing the pairing code") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "waitForApp", stepData = QrPairingStep("PENDING1"))
            }
        }
    }

    given("patch() on a pairing that ran out before the app decided") {
        val toolSessionId = sessionOn("EXPIRED1", QrLoginStatus.PENDING, expiresAt = Instant.now().minusSeconds(1))

        `when`("the browser polls") {
            val outcome = handler.patch(toolSessionId, confirmationCode = null)

            then("it fails as expired") {
                outcome shouldBe ToolOutcome.Failed.IdentifiedAuth(Text("QR-Code abgelaufen"))
            }
        }
    }

    given("patch() after account 42 approved in the app") {
        val toolSessionId = sessionOn("APPROVE1", QrLoginStatus.APPROVED, resolvingAccountId = 42L)
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

            then("it fails and counts the wrong code on the pairing") {
                outcome shouldBe ToolOutcome.Failed.IdentifiedAuth(Text("Bestätigungscode falsch"))
                verify { requests.countWrongConfirmation("APPROVE1", any()) }
            }
        }

        `when`("the browser sends the right confirmation code") {
            val outcome = handler.patch(toolSessionId, confirmationCode = "123456")

            then("it authenticates at the descriptor's own maxAcr and factorTypes, naming no account") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                    amr = listOf("qr"),
                    achievedAcr = AuthQrDescriptor.maxAcr,
                    factorTypes = AuthQrDescriptor.factorTypes,
                )
            }
        }
    }

    given("patch() after a different account than the expected one approved") {
        val toolSessionId = sessionOn("FOREIGN1", QrLoginStatus.APPROVED, expectedAccountId = 42L, resolvingAccountId = 99L)
        every { requests.completeIfConfirmed("FOREIGN1", digest.of("123456"), any()) } returns 1

        `when`("the browser sends the right confirmation code") {
            val outcome = handler.patch(toolSessionId, confirmationCode = "123456")

            then("it fails instead of silently switching the account") {
                outcome shouldBe ToolOutcome.Failed.IdentifiedAuth(Text("Bestätigung passt nicht zu diesem Konto"))
            }
        }
    }

    given("patch() after the app declined") {
        val toolSessionId = sessionOn("DENIED01", QrLoginStatus.DENIED)

        `when`("the browser polls") {
            val outcome = handler.patch(toolSessionId, confirmationCode = null)

            then("it fails as declined") {
                outcome shouldBe ToolOutcome.Failed.IdentifiedAuth(Text("Vom Nutzer abgelehnt"))
            }
        }
    }

    given("read() after the app declined") {
        val toolSessionId = sessionOn("DENIED02", QrLoginStatus.DENIED)

        `when`("the page is reloaded") {
            val outcome = handler.read(toolSessionId)

            then("it reads as closed and leaves reporting the outcome to the next PATCH") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "closed")
            }
        }
    }
})
