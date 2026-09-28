package com.example.identity.tools.auth_qr.internal.confirmqrlogin

import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_qr.ConfirmQrLoginDescriptor
import com.example.identity.tools.auth_qr.api.v1.QrPairingStep
import com.example.identity.tools.auth_qr.internal.QrLoginRequest
import com.example.identity.tools.auth_qr.internal.ConfirmationCodeDigest
import com.example.identity.tools.auth_qr.internal.QrLoginRequestRepository
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/**
 * Unit test of accept/reject and the expectedAccountId guard: `confirm-qr-login` fails fast in the
 * app when it can never satisfy the web side's `auth-qr` step-up (matching the late check in
 * [com.example.identity.tools.auth_qr.internal.authqr.AuthQrToolHandler]).
 */
class ConfirmQrLoginToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<ConfirmQrLoginToolSessionRepository>()
    val qrLoginRequestRepository = mockk<QrLoginRequestRepository>()
    val handler = ConfirmQrLoginToolHandler(ConfirmQrLoginDescriptor, toolDataRepository, qrLoginRequestRepository, ConfirmationCodeDigest("test-pepper"))
    val toolSessionId = UUID.randomUUID()
    val pairingCode = "ABCD1234"

    given("a confirm-qr-login tool session that already resolved its pairing code") {
        val data = ConfirmQrLoginToolSession(toolSessionId = toolSessionId).apply { this.pairingCode = pairingCode }
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("the pairing's expectedAccountId belongs to a DIFFERENT account than the one confirming") {
            every { qrLoginRequestRepository.findById(pairingCode) } returns Optional.of(
                QrLoginRequest(pairingCode = pairingCode, expectedAccountId = 42L)
            )

            then("it fails immediately, never reaching approveIfPending") {
                val outcome = handler.patch(toolSessionId, pairingCode = null, decision = "accept", accountId = 99L, hasQrEnrollment = true)

                outcome shouldBe ToolOutcome.Failed.NothingGuessed(Text("Bestätigung passt nicht zu diesem Konto"))
            }
        }

        `when`("the pairing's expectedAccountId matches the confirming account") {
            every { qrLoginRequestRepository.findById(pairingCode) } returns Optional.of(
                QrLoginRequest(pairingCode = pairingCode, expectedAccountId = 99L)
            )
            every { qrLoginRequestRepository.approveIfPending(pairingCode, 99L, any(), any(), any()) } returns 1

            then("it approves and shows the six-digit code for the browser - it does not finish yet") {
                val outcome = handler.patch(toolSessionId, pairingCode = null, decision = "accept", accountId = 99L, hasQrEnrollment = true)

                outcome.shouldBeApproved()
            }
        }

        `when`("expectedAccountId is null (auth-qr-lookup, no target account to violate)") {
            every { qrLoginRequestRepository.findById(pairingCode) } returns Optional.of(
                QrLoginRequest(pairingCode = pairingCode, expectedAccountId = null)
            )
            every { qrLoginRequestRepository.approveIfPending(pairingCode, 99L, any(), any(), any()) } returns 1

            then("it approves - any account may confirm - and shows the code for the browser") {
                val outcome = handler.patch(toolSessionId, pairingCode = null, decision = "accept", accountId = 99L, hasQrEnrollment = true)

                outcome.shouldBeApproved()
            }
        }

        `when`("the account has no active qr enrollment at all") {
            then("it fails before even looking at expectedAccountId") {
                val outcome = handler.patch(toolSessionId, pairingCode = null, decision = "accept", accountId = 99L, hasQrEnrollment = false)

                outcome shouldBe ToolOutcome.Failed.NothingGuessed(Text("QR-Login ist für dieses Konto nicht aktiviert."))
            }
        }
    }
})

/** Approval shows the confirmation code; `done` finishes the tool later. */
private fun ToolOutcome.shouldBeApproved() {
    val step = this.shouldBeInstanceOf<ToolOutcome.InProgress>()
    step.nextStep shouldBe "showCode"
    step.stepData.shouldBeInstanceOf<QrPairingStep>().confirmationCode!! shouldMatch Regex("\\d{6}")
}
