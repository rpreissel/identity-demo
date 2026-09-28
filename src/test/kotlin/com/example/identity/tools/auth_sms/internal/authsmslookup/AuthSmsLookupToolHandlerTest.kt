package com.example.identity.tools.auth_sms.internal.authsmslookup
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.SmsSendBudget
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollment

import com.example.identity.tools.auth_sms.AuthSmsLookupDescriptor
import com.example.identity.tools.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the tan-vs-state decision is covered by [AuthSmsLookupFlowTest].
 */
class AuthSmsLookupToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthSmsLookupToolSessionRepository>()
    val enrollmentRepository = mockk<AuthSmsEnrollmentRepository>()
    val tanGenerator = TanGenerator("test-pepper", clock = TEST_CLOCK)
    val sendBudget = mockk<SmsSendBudget>(relaxed = true).also { every { it.trySend(any()) } returns true }
    val handler = AuthSmsLookupToolHandler(AuthSmsLookupDescriptor, toolDataRepository, enrollmentRepository, tanGenerator, SmsGateway(clock = TEST_CLOCK), sendBudget, mockk(relaxed = true), clock = TEST_CLOCK)
    val toolSessionId = UUID.randomUUID()

    given("an active auth-sms-lookup tool session") {
        val data = AuthSmsLookupToolSession(toolSessionId = toolSessionId, createdAt = TEST_NOW)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("the submitted email resolves to an account with an active sms method") {
            val enrollment = AuthSmsEnrollment(phoneNumber = "+491701234567", createdAt = TEST_NOW).apply { id = 1L }
            every { enrollmentRepository.findById(1L) } returns Optional.of(enrollment)
            val saved = slot<AuthSmsLookupToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }

            then("it persists the resolved account and a fresh TAN, revealing the demo TAN") {
                val outcome = handler.submitEmail(toolSessionId, accountId = 42L, enrollmentRef = EnrollmentRef(SMS_ENROLLMENT_TYPE, "1"))

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                outcome.nextStep shouldBe "tanInput"
                // Der Demo-Anteil ist ein eigenes Feld, kein reservierter Schluessel in den Schrittdaten.
                outcome.demo?.get("tan").shouldNotBeNull()
                saved.captured.accountId shouldBe 42L
                saved.captured.issuedTanHash.shouldNotBeNull()
            }
        }

        `when`("the account's number has used up its send budget") {
            val enrollment = AuthSmsEnrollment(phoneNumber = "+491709999999", createdAt = TEST_NOW).apply { id = 3L }
            every { enrollmentRepository.findById(3L) } returns Optional.of(enrollment)
            every { sendBudget.trySend("+491709999999") } returns false
            val saved = slot<AuthSmsLookupToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }

            then("it answers exactly like for an unknown address: no TAN sent, no account stored") {
                val outcome = handler.submitEmail(toolSessionId, accountId = 42L, enrollmentRef = EnrollmentRef(SMS_ENROLLMENT_TYPE, "3"))

                (outcome as ToolOutcome.InProgress).nextStep shouldBe "tanInput"
                outcome.demo?.get("tan") shouldBe null
                saved.captured.accountId shouldBe null
            }
        }

        `when`("the submitted email does not resolve to anything (enumeration protection)") {
            val saved = slot<AuthSmsLookupToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }

            then("it still issues a TAN, but reveals no demo TAN and stores no account") {
                val outcome = handler.submitEmail(toolSessionId, accountId = null, enrollmentRef = null)

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                outcome.nextStep shouldBe "tanInput"
                outcome.demo?.get("tan") shouldBe null
                saved.captured.accountId shouldBe null
            }
        }
    }

    given("a resolved account with a pending TAN") {
        val issued = tanGenerator.issue()
        val data = AuthSmsLookupToolSession(toolSessionId = toolSessionId, accountId = 42L, issuedTanHash = issued.hash, tanExpiresAt = issued.expiresAt, createdAt = TEST_NOW)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("confirming with the correct TAN") {
            then("it authenticates for that account") {
                val outcome = handler.patch(toolSessionId, issued.plainTan)

                val authenticated = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                authenticated.accountId shouldBe 42L
                authenticated.amr shouldBe listOf("sms")
            }
        }
    }
})
