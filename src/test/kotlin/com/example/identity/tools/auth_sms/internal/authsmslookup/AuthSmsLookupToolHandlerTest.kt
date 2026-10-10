package com.example.identity.tools.auth_sms.internal.authsmslookup
import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.contract.tool_api.otp.OneTimeCodes
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.tools.auth_sms.internal.SmsNumbers
import com.example.identity.core.account.application.ClaimCryptoFixture
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.contract.tool_api.Subject
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.SmsSendLimit
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollment

import com.example.identity.tools.auth_sms.internal.SMS_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import java.util.UUID

private const val PHONE = "+491701234567"
private val ACCOUNT = AccountId(42L)
private val SMS_REF = EnrollmentRef(SMS_ENROLLMENT_TYPE, "1")

/** What every unresolved submission answers: the TAN step, no demo TAN, nothing naming an account. */
private val NEUTRAL_ANSWER = ToolOutcome.InProgress(nextStep = "tanInput", stepData = MissingFields(listOf("tan")), demo = emptyMap())

/**
 * [ACCOUNT] has [PHONE] as its active SMS method and the send budget is open. Each fixture holds a
 * fresh session, so no submission sees another's writes.
 */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = InMemoryToolSessionData().also { it.save(toolSessionId, AuthSmsLookupToolSession()) }

    /** The session as the handler last saved it. */
    val session: AuthSmsLookupToolSession get() = sessions.stored(toolSessionId)
    val keys = ClaimCryptoFixture()
    val smsNumbers = SmsNumbers(keys.sealing)
    val enrollments = mockk<AuthSmsEnrollmentRepository>().also {
        every { it.findById(1L) } returns Optional.of(smsNumbers.newEnrollment(PHONE, keys.newKey(), TEST_NOW).apply { id = 1L })
    }
    val accountDirectory = mockk<AccountDirectory>().also {
        every { it.activeEnrollment(ACCOUNT, "sms") } returns SMS_REF
    }
    val tans = TanGenerator("test-pepper", clock = TEST_CLOCK)
    val sendLimit = mockk<SmsSendLimit>(relaxed = true).also { every { it.trySend(any()) } returns true }
    val gateway = SmsGateway(clock = TEST_CLOCK)
    val lockouts = mockk<Lockouts> { every { admitAttempt(any()) } returns true }
    val handler = AuthSmsLookupToolHandler(sessions, enrollments, tans, gateway, sendLimit, accountDirectory, smsNumbers, lockouts)

    fun withAccountLocked() = apply {
        every { lockouts.admitAttempt(any()) } returns false
    }

    fun withSendBudgetUsedUp() = apply {
        every { sendLimit.trySend(PHONE) } returns false
    }

    /** Puts a pending TAN for [accountId] into the session and returns it. */
    fun withPendingTan(accountId: AccountId?): OneTimeCodes.Issued = tans.issue().also { issued ->
        sessions.save(toolSessionId, AuthSmsLookupToolSession(accountId, issued.hash, issued.expiresAt))
    }
}

/**
 * Pure unit test: no Spring context, repositories and the account directory mocked with MockK.
 * Covers persistence/outcome wiring and the enumeration-neutral answers of [AuthSmsLookupToolHandler.submitEmail];
 * the tan-vs-state decision is covered by [AuthSmsLookupFlowTest].
 */
class AuthSmsLookupToolHandlerTest : BehaviorSpec({

    given("no auth-sms-lookup tool session yet") {
        val f = Fixture()

        `when`("a lookup login begins") {
            val started = ToolSessionId(UUID.randomUUID())
            val outcome = f.handler.start(started)
            val saved = f.sessions.stored<AuthSmsLookupToolSession>(started)

            then("it asks for the email at step auth and stores no TAN yet") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "auth", stepData = MissingFields(listOf("email")), demo = emptyMap())
                saved.issuedTanHash shouldBe null
                saved.accountId shouldBe null
            }
        }
    }

    given("a session awaiting the email, which resolves to an account with an active sms method") {
        val f = Fixture()

        `when`("the email is submitted") {
            val outcome = f.handler.submitEmail(f.toolSessionId, accountId = ACCOUNT, enrollmentRef = SMS_REF)
            val sms = f.gateway.outbox().single()

            then("it texts a TAN to the number and asks for it, revealing it as the demo value") {
                sms.phoneNumber shouldBe PHONE
                outcome shouldBe ToolOutcome.InProgress(nextStep = "tanInput", stepData = MissingFields(listOf("tan")), demo = mapOf("tan" to sms.tan))
            }

            then("it stores the account and the hash of the texted TAN") {
                f.session.accountId shouldBe ACCOUNT
                f.tans.matches(sms.tan, f.session.issuedTanHash, f.session.tanExpiresAt) shouldBe true
            }
        }
    }

    given("a session awaiting the email, which resolves to nothing (enumeration protection)") {
        val f = Fixture()

        `when`("the email is submitted") {
            val outcome = f.handler.submitEmail(f.toolSessionId, accountId = null, enrollmentRef = null)

            then("it still issues a TAN, but sends none, reveals none and stores no account") {
                outcome shouldBe NEUTRAL_ANSWER
                f.session.issuedTanHash.shouldNotBeNull()
                f.session.accountId shouldBe null
                f.gateway.outbox().shouldBeEmpty()
            }
        }
    }

    given("a session awaiting the email, which resolves to an account whose number has used up its send budget") {
        val f = Fixture().withSendBudgetUsedUp()

        `when`("the email is submitted") {
            val outcome = f.handler.submitEmail(f.toolSessionId, accountId = ACCOUNT, enrollmentRef = SMS_REF)

            then("it answers exactly like for an unknown address and stores no account") {
                outcome shouldBe NEUTRAL_ANSWER
                f.session.accountId shouldBe null
                f.gateway.outbox().shouldBeEmpty()
            }
        }
    }

    given("a resolved account with a pending TAN") {
        val f = Fixture()
        val issued = f.withPendingTan(ACCOUNT)

        `when`("confirming with the correct TAN") {
            val outcome = f.handler.patch(f.toolSessionId, issued.plain)

            then("it authenticates for that account at its tool's own level and factors") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                    subject = Subject.Account(ACCOUNT),
                )
            }

            then("the number's send budget starts over") {
                verify { f.sendLimit.received(PHONE) }
            }
        }
    }

    given("another resolved account with a pending TAN") {
        val f = Fixture()
        f.withPendingTan(ACCOUNT)

        `when`("submitting a wrong TAN") {
            val outcome = f.handler.patch(f.toolSessionId, "000000")

            then("it fails against the resolved account, whose attempt was booked before the check") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(Text("E-Mail oder TAN ungueltig"), attempted = Attempted.Account(ACCOUNT))
                verify { f.lockouts.admitAttempt(ACCOUNT) }
            }
        }
    }

    given("a resolved account with a pending TAN, locked by now") {
        val f = Fixture().withAccountLocked()
        val issued = f.withPendingTan(ACCOUNT)

        `when`("submitting even the issued TAN") {
            val outcome = f.handler.patch(f.toolSessionId, issued.plain)

            then("it fails like a wrong TAN, without checking it") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(Text("E-Mail oder TAN ungueltig"), attempted = null)
            }
        }
    }

    given("an unresolved email with a pending TAN") {
        val f = Fixture()
        val issued = f.withPendingTan(null)

        `when`("submitting even the issued TAN") {
            val outcome = f.handler.patch(f.toolSessionId, issued.plain)

            then("it fails with the same wording and names no account to charge") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(Text("E-Mail oder TAN ungueltig"), attempted = null)
            }
        }
    }
})
