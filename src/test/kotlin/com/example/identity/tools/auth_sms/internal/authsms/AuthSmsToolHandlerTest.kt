package com.example.identity.tools.auth_sms.internal.authsms
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.SmsSendBudget
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollment

import com.example.identity.tools.auth_sms.AuthSmsDescriptor
import com.example.identity.tools.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.kotest.matchers.collections.shouldBeEmpty
import com.example.identity.contract.tool_api.TooManyRequestsException
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the tan-vs-state decision is covered by [AuthSmsFlowTest].
 */
class AuthSmsToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthSmsToolSessionRepository>()
    val enrollmentRepository = mockk<AuthSmsEnrollmentRepository>()
    val tanGenerator = TanGenerator("test-pepper")
    val sendBudget = mockk<SmsSendBudget>(relaxed = true).also { every { it.trySend(any()) } returns true }
    val handler = AuthSmsToolHandler(AuthSmsDescriptor, toolDataRepository, enrollmentRepository, tanGenerator, SmsGateway(), sendBudget)
    val toolSessionId = UUID.randomUUID()

    given("start()") {
        `when`("the enrollment reference has the wrong type") {
            val result = runCatching { handler.start(toolSessionId, EnrollmentRef("auth_device.enrollment", "1")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("the referenced enrollment does not exist") {
            every { enrollmentRepository.findById(99L) } returns Optional.empty()
            val result = runCatching { handler.start(toolSessionId, EnrollmentRef(SMS_ENROLLMENT_TYPE, "99")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("the referenced enrollment exists") {
            val enrollment = AuthSmsEnrollment(phoneNumber = "+491701234567").apply { id = 1L }
            every { enrollmentRepository.findById(1L) } returns Optional.of(enrollment)
            val saved = slot<AuthSmsToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }
            val outcome = handler.start(toolSessionId, EnrollmentRef(SMS_ENROLLMENT_TYPE, "1"))

            then("it persists a fresh TAN and asks for it at step auth") {
                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                outcome.nextStep shouldBe "auth"
                saved.captured.issuedTanHash.shouldNotBeNull()
            }
        }

        `when`("the number's send budget is used up") {
            val enrollment = AuthSmsEnrollment(phoneNumber = "+491707654321").apply { id = 2L }
            every { enrollmentRepository.findById(2L) } returns Optional.of(enrollment)
            every { sendBudget.trySend("+491707654321") } returns false
            val gateway = SmsGateway()
            val throttledHandler = AuthSmsToolHandler(AuthSmsDescriptor, toolDataRepository, enrollmentRepository, tanGenerator, gateway, sendBudget)
            val result = runCatching { throttledHandler.start(toolSessionId, EnrollmentRef(SMS_ENROLLMENT_TYPE, "2")) }

            then("it refuses with TooManyRequestsException and sends nothing") {
                shouldThrow<TooManyRequestsException> { result.getOrThrow() }
                gateway.outbox().shouldBeEmpty()
            }
        }
    }

    given("an active auth-sms tool session with a pending TAN") {
        val issued = tanGenerator.issue()
        val data = AuthSmsToolSession(toolSessionId = toolSessionId, enrollmentRefId = "1", issuedTanHash = issued.hash, tanExpiresAt = issued.expiresAt)
        every { enrollmentRepository.findById(1L) } returns Optional.of(AuthSmsEnrollment(phoneNumber = "+491701234567").apply { id = 1L })
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)

        `when`("confirming with the correct TAN") {
            val outcome = handler.patch(toolSessionId, issued.plainTan)

            then("it authenticates at the descriptor's own maxAcr and factorTypes") {
                val authenticated = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                authenticated.amr shouldBe listOf("sms")
                authenticated.achievedAcr shouldBe AuthSmsDescriptor.maxAcr
                authenticated.factorTypes shouldBe AuthSmsDescriptor.factorTypes
            }

            then("the number's send budget starts over: whoever asked received the TAN") {
                verify { sendBudget.received("+491701234567") }
            }
        }
    }

    given("an auth-sms tool session whose enrollment was removed meanwhile, on another channel") {
        val goneSessionId = UUID.randomUUID()
        val issued = tanGenerator.issue()
        every { toolDataRepository.findById(goneSessionId) } returns
            Optional.of(AuthSmsToolSession(toolSessionId = goneSessionId, enrollmentRefId = "7", issuedTanHash = issued.hash, tanExpiresAt = issued.expiresAt))
        every { enrollmentRepository.findById(7L) } returns Optional.empty()

        `when`("the right TAN arrives") {
            val result = runCatching { handler.patch(goneSessionId, issued.plainTan) }

            then("it authenticates nobody and counts nothing: the reference is unresolvable") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }
})
