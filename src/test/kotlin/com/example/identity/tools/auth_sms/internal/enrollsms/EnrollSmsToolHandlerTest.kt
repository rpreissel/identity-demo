package com.example.identity.tools.auth_sms.internal.enrollsms
import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.tools.auth_sms.internal.SmsNumbers
import com.example.identity.core.account.application.ClaimCryptoFixture
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_sms.PHONE_NUMBER
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollment
import com.example.identity.tools.auth_sms.internal.SmsSendLimit

import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.tools.auth_sms.api.v1.EnrollSmsStep
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.assertClaimsCovered
import com.example.identity.contract.tool_api.TooManyRequestsException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

private const val PHONE = "+491701234567"

/** No tool session exists and the send budget is open until a test changes that. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = InMemoryToolSessionData()
    val enrollments = mockk<AuthSmsEnrollmentRepository>().also {
        every { it.save(any()) } answers { firstArg<AuthSmsEnrollment>().apply { id = 42L } }
    }
    // Explicit pepper so issue()/matches() stay reproducible within the test run.
    val tans = TanGenerator("test-pepper", clock = TEST_CLOCK)
    val sendLimit = mockk<SmsSendLimit>(relaxed = true).also { every { it.trySend(any()) } returns true }
    val gateway = SmsGateway(clock = TEST_CLOCK)
    val keys = ClaimCryptoFixture()
    val keyId = keys.newKey()
    val handler = EnrollSmsToolHandler(sessions, enrollments, tans, gateway, sendLimit, SmsNumbers(keys.sealing), clock = TEST_CLOCK)

    /** Stores [session] for the handler to read and write. */
    fun withSession(session: EnrollSmsToolSession) {
        sessions.save(toolSessionId, session)
    }

    /** The session as the handler last saved it. */
    val session: EnrollSmsToolSession get() = sessions.stored(toolSessionId)

    fun withSendBudgetUsedUp(number: String) = apply {
        every { sendLimit.trySend(number) } returns false
    }
}

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the decision branches (invalid phone, wrong tan, ambiguous combinations) are
 * covered by [EnrollSmsFlowTest].
 */
class EnrollSmsToolHandlerTest : BehaviorSpec({

    given("no enroll-sms tool session yet") {
        val f = Fixture()

        `when`("a tool session starts") {
            val outcome = f.handler.start(f.toolSessionId, version = 1)

            then("it asks for the phone number at step enroll") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "enroll", stepData = EnrollSmsStep(listOf("phoneNumber"), replaces = false))
            }
        }
    }

    given("an active enroll-sms tool session with no phone number yet") {
        val f = Fixture()
        f.withSession(EnrollSmsToolSession())

        `when`("submitting a valid phone number") {
            val outcome = f.handler.patch(f.toolSessionId, version = 1, phoneNumber = "+49 170 1234567", tan = null, masterKey = { f.keyId })
            val sms = f.gateway.outbox().single()

            then("it texts a TAN to the normalized number and asks for it at step tanInput, revealing it as the demo value") {
                sms.phoneNumber shouldBe PHONE
                outcome shouldBe ToolOutcome.InProgress(nextStep = "tanInput", stepData = EnrollSmsStep(listOf("tan"), replaces = false), demo = mapOf("tan" to sms.tan))
            }

            then("it persists the normalized number and the hash of the texted TAN") {
                f.session.phoneNumber shouldBe PHONE
                f.tans.matches(sms.tan, f.session.issuedTanHash, f.session.tanExpiresAt) shouldBe true
            }
        }
    }

    given("version 2 of enroll-sms (ADR-51)") {
        `when`("a tool session starts") {
            val f = Fixture()
            val outcome = f.handler.start(f.toolSessionId, version = 2)

            then("it asks for the phone number and the consent") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "enroll", stepData = EnrollSmsStep(listOf("phoneNumber", "consent"), replaces = false))
            }
        }

        `when`("a phone number comes without the consent") {
            val f = Fixture()
            f.withSession(EnrollSmsToolSession())
            val outcome = f.handler.patch(f.toolSessionId, version = 2, phoneNumber = "+49 170 1234567", tan = null, masterKey = { f.keyId })

            then("no SMS goes out and the consent is still missing") {
                f.gateway.outbox() shouldBe emptyList()
                outcome shouldBe ToolOutcome.InProgress(nextStep = "enroll", stepData = EnrollSmsStep(listOf("phoneNumber", "consent"), replaces = false))
            }
        }

        `when`("a phone number comes with the consent") {
            val f = Fixture()
            f.withSession(EnrollSmsToolSession())
            val outcome = f.handler.patch(f.toolSessionId, version = 2, phoneNumber = "+49 170 1234567", tan = null, consent = true, masterKey = { f.keyId })
            val sms = f.gateway.outbox().single()

            then("the TAN goes out, and the run remembers the consent for a corrected number") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "tanInput", stepData = EnrollSmsStep(listOf("tan"), replaces = false), demo = mapOf("tan" to sms.tan))
                f.session.consented shouldBe true
            }
        }
    }

    given("an active enroll-sms tool session, and a number that has used up its send budget") {
        val f = Fixture().withSendBudgetUsedUp("+491709999999")
        f.withSession(EnrollSmsToolSession())

        `when`("submitting that number") {
            val result = runCatching { f.handler.patch(f.toolSessionId, version = 1, phoneNumber = "+49 170 9999999", tan = null, masterKey = { f.keyId }) }

            then("it refuses with 429 - nothing was guessed, the caller chose the number") {
                shouldThrow<TooManyRequestsException> { result.getOrThrow() }
            }

            then("it issues and sends no TAN") {
                f.session.issuedTanHash shouldBe null
                f.gateway.outbox().shouldBeEmpty()
            }
        }
    }

    given("an active enroll-sms tool session with a phone number and a valid, unexpired TAN") {
        val f = Fixture()
        val issued = f.tans.issue()
        f.withSession(
            EnrollSmsToolSession(
                phoneNumber = PHONE,
                issuedTanHash = issued.hash,
                tanExpiresAt = issued.expiresAt)
        )

        `when`("confirming with the correct TAN") {
            val outcome = f.handler.patch(f.toolSessionId, version = 1, phoneNumber = null, tan = issued.plainTan, masterKey = { f.keyId })

            // The confirmed number is an assertion about the subject, so it reaches the account's
            // claim log (AccountService.recordClaims) - in its normalized form, not as typed.
            then("it enrolls the credential at its tool's own level and factors, asserting the number as a PHONE_NUMBER claim") {
                outcome shouldBe ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef("auth_sms.enrollment", "42"),
                    claims = listOf(Claim(PHONE_NUMBER, PHONE, ClaimSource(tool("enroll-sms").toolId.value), tool("enroll-sms").maxAcr)),
                )
            }

            then("the claim passes the contract check JourneyService runs before adopting the outcome") {
                assertClaimsCovered(tool("enroll-sms"), outcome.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>().claims)
            }

            then("the number's send budget starts over: whoever asked received the TAN") {
                verify { f.sendLimit.received(PHONE) }
            }
        }
    }
})
