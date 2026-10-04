package com.example.identity.tools.auth_password.internal.authpasswordlookup
import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.contract.tool_api.Subject
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_password.internal.PasswordHasher
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollment

import com.example.identity.tools.auth_password.DEMO_PASSWORD
import com.example.identity.tools.auth_password.internal.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.util.Optional
import java.util.UUID

private val ACCOUNT = AccountId(42L)
private val PASSWORD_REF = EnrollmentRef(PASSWORD_ENROLLMENT_TYPE, "1")

/** The one reason every failed login gives, known address or not (enumeration protection). */
private val WRONG_ANSWER = Text("E-Mail oder Passwort ungueltig")

/** One active tool session; enrollment 1 holds the password "hunter2". */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = InMemoryToolSessionData().also { it.save(toolSessionId, AuthPasswordLookupToolSession()) }
    val enrollments = mockk<AuthPasswordEnrollmentRepository>().also {
        every { it.findById(1L) } returns Optional.of(AuthPasswordEnrollment(passwordHash = PasswordHasher.hash("hunter2"), createdAt = TEST_NOW).apply { id = 1L })
    }
    val handler = AuthPasswordLookupToolHandler(sessions, enrollments)
}

/**
 * Pure unit test: no Spring context, the session data kept in memory, repositories mocked with MockK. Covers persistence/outcome
 * wiring and the enumeration-neutral failure; the completeness decision is covered by
 * [AuthPasswordLookupFlowTest].
 */
class AuthPasswordLookupToolHandlerTest : BehaviorSpec({

    afterSpec { unmockkObject(PasswordHasher) }

    given("no auth-password-lookup tool session yet") {
        val f = Fixture()

        `when`("a tool session starts") {
            val outcome = f.handler.start(ToolSessionId(UUID.randomUUID()))

            then("it asks for email and password at step auth, offering the demo password") {
                outcome shouldBe ToolOutcome.InProgress(
                    nextStep = "auth",
                    stepData = MissingFields(listOf("email", "password")),
                    demo = mapOf("password" to DEMO_PASSWORD),
                )
            }
        }
    }

    given("an active auth-password-lookup tool session") {
        val f = Fixture()

        `when`("the form is read before anything is entered") {
            val outcome = f.handler.read(f.toolSessionId)

            then("it carries the demo password, so the login form comes pre-filled like auth-password's") {
                outcome shouldBe ToolOutcome.InProgress(
                    nextStep = "auth",
                    stepData = MissingFields(listOf("email", "password")),
                    demo = mapOf("password" to DEMO_PASSWORD),
                )
            }
        }

        `when`("only the email arrives") {
            val outcome = f.handler.patch(f.toolSessionId, email = "max@example.com", password = null, accountId = ACCOUNT, enrollmentRef = PASSWORD_REF)

            then("it asks for the password alone") {
                outcome shouldBe ToolOutcome.InProgress(
                    nextStep = "auth",
                    stepData = MissingFields(listOf("password")),
                    demo = mapOf("password" to DEMO_PASSWORD),
                )
            }
        }

        `when`("email and password resolve to an active, matching enrollment") {
            val outcome = f.handler.patch(f.toolSessionId, email = "max@example.com", password = "hunter2", accountId = ACCOUNT, enrollmentRef = PASSWORD_REF)

            then("it authenticates for that account at its tool's own level and factors") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                    subject = Subject.Account(ACCOUNT),
                )
            }
        }

        `when`("the email resolves to an account, but the password is wrong") {
            val outcome = f.handler.patch(f.toolSessionId, email = "max@example.com", password = "wrong", accountId = ACCOUNT, enrollmentRef = PASSWORD_REF)

            then("it fails with the constant-shape message, naming the account for the orchestrator to charge") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(WRONG_ANSWER, attempted = Attempted.Account(ACCOUNT))
            }
        }

        `when`("the email resolves to an account without a password method") {
            val outcome = f.handler.patch(f.toolSessionId, email = "max@example.com", password = "hunter2", accountId = ACCOUNT, enrollmentRef = null)

            then("it fails with the constant-shape message, naming the account") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(WRONG_ANSWER, attempted = Attempted.Account(ACCOUNT))
            }
        }

        `when`("the email never resolved to anything (enumeration protection)") {
            mockkObject(PasswordHasher)
            val outcome = f.handler.patch(f.toolSessionId, email = "unknown@example.com", password = "hunter2", accountId = null, enrollmentRef = null)

            then("it fails with the same constant-shape message, naming no account") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(WRONG_ANSWER, attempted = null)
            }

            then("it still runs the password check, against no hash, which spends the full Argon2id work") {
                verify(exactly = 1) { PasswordHasher.matches("hunter2", null) }
            }
        }
    }
})
