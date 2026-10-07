package com.example.identity.tools.auth_sms.internal.authsms
import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.tools.auth_sms.internal.SmsNumbers
import com.example.identity.core.account.application.ClaimCryptoFixture
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.SmsSendLimit
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollment

import com.example.identity.tools.auth_sms.internal.SMS_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.kotest.matchers.collections.shouldBeEmpty
import com.example.identity.contract.tool_api.TooManyRequestsException
import java.util.Optional
import java.util.UUID

private const val PHONE = "+491701234567"

/** No SMS enrollment and no tool session exist, and the send budget is open, until a test changes that. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = InMemoryToolSessionData()
    val enrollments = mockk<AuthSmsEnrollmentRepository>().also {
        every { it.findById(any()) } returns Optional.empty()
    }
    val tans = TanGenerator("test-pepper", clock = TEST_CLOCK)
    val sendLimit = mockk<SmsSendLimit>(relaxed = true).also { every { it.trySend(any()) } returns true }
    val gateway = SmsGateway(clock = TEST_CLOCK)
    val keys = ClaimCryptoFixture()
    val smsNumbers = SmsNumbers(keys.sealing)
    val handler = AuthSmsToolHandler(sessions, enrollments, tans, gateway, sendLimit, smsNumbers)

    fun withEnrolledNumber(id: Long) = apply {
        every { enrollments.findById(id) } returns Optional.of(smsNumbers.newEnrollment(PHONE, keys.newKey(), TEST_NOW).apply { this.id = id })
    }

    fun withSendBudgetUsedUp() = apply {
        every { sendLimit.trySend(PHONE) } returns false
    }

    /** Persists a pending TAN for [toolSessionId], bound to [enrollmentRefId], and returns it. */
    fun withPendingTan(enrollmentRefId: String): TanGenerator.Issued = tans.issue().also { issued ->
        sessions.save(toolSessionId, AuthSmsToolSession(enrollmentRefId = enrollmentRefId, issuedTanHash = issued.hash, tanExpiresAt = issued.expiresAt))
    }
}

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the tan-vs-state decision is covered by [AuthSmsFlowTest].
 */
class AuthSmsToolHandlerTest : BehaviorSpec({

    given("no SMS enrollment") {
        val f = Fixture()

        `when`("a tool session starts with an enrollment reference of the wrong type") {
            val result = runCatching { f.handler.start(f.toolSessionId, EnrollmentRef("auth_device.enrollment", "1")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("a tool session starts with a reference to a missing enrollment") {
            val result = runCatching { f.handler.start(f.toolSessionId, EnrollmentRef(SMS_ENROLLMENT_TYPE, "99")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }

    given("an enrolled phone number") {
        val f = Fixture().withEnrolledNumber(1L)

        `when`("a tool session starts with a reference to it") {
            val outcome = f.handler.start(f.toolSessionId, EnrollmentRef(SMS_ENROLLMENT_TYPE, "1"))
            val sms = f.gateway.outbox().single()

            then("it texts a TAN to the number and asks for it at step auth, revealing it as the demo value") {
                sms.phoneNumber shouldBe PHONE
                outcome shouldBe ToolOutcome.InProgress(nextStep = "auth", stepData = MissingFields(listOf("tan")), demo = mapOf("tan" to sms.tan))
            }

            then("it persists the hash of the texted TAN") {
                val stored = f.sessions.stored<AuthSmsToolSession>(f.toolSessionId)
                f.tans.matches(sms.tan, stored.issuedTanHash, stored.tanExpiresAt) shouldBe true
            }
        }
    }

    given("an enrolled phone number whose send budget is used up") {
        val f = Fixture().withEnrolledNumber(2L).withSendBudgetUsedUp()

        `when`("a tool session starts with a reference to it") {
            val result = runCatching { f.handler.start(f.toolSessionId, EnrollmentRef(SMS_ENROLLMENT_TYPE, "2")) }

            then("it refuses with TooManyRequestsException") {
                shouldThrow<TooManyRequestsException> { result.getOrThrow() }
            }

            then("it sends nothing") {
                f.gateway.outbox().shouldBeEmpty()
            }
        }
    }

    given("an active auth-sms tool session with a pending TAN") {
        val f = Fixture().withEnrolledNumber(1L)
        val issued = f.withPendingTan("1")

        `when`("confirming with the correct TAN") {
            val outcome = f.handler.patch(f.toolSessionId, issued.plainTan)

            then("it authenticates at its tool's own level and factors") {
                val authenticated = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                authenticated.amr shouldBe null
                authenticated.achievedAcr shouldBe null
                authenticated.factorTypes shouldBe null
            }

            then("the number's send budget starts over: whoever asked received the TAN") {
                verify { f.sendLimit.received(PHONE) }
            }
        }
    }

    given("an auth-sms tool session whose enrollment was removed meanwhile, on another channel") {
        val f = Fixture()
        val issued = f.withPendingTan("7")

        `when`("the right TAN arrives") {
            val result = runCatching { f.handler.patch(f.toolSessionId, issued.plainTan) }

            then("it authenticates nobody and counts nothing: the reference is unresolvable") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }
})
