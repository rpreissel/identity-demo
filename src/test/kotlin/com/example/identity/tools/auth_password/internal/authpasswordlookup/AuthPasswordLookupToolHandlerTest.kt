package com.example.identity.tools.auth_password.internal.authpasswordlookup
import com.example.identity.contract.tool_api.Subject
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_password.internal.PasswordHasher
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollment

import com.example.identity.tools.auth_password.AuthPasswordLookupDescriptor
import com.example.identity.tools.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the completeness decision is covered by [AuthPasswordLookupFlowTest].
 */
class AuthPasswordLookupToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthPasswordLookupToolSessionRepository>()
    val enrollmentRepository = mockk<AuthPasswordEnrollmentRepository>()
    val handler = AuthPasswordLookupToolHandler(AuthPasswordLookupDescriptor, toolDataRepository, enrollmentRepository, clock = TEST_CLOCK)
    val toolSessionId = UUID.randomUUID()

    given("an active auth-password-lookup tool session") {
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(AuthPasswordLookupToolSession(toolSessionId = toolSessionId, createdAt = TEST_NOW))

        `when`("email and password resolve to an active, matching enrollment") {
            val enrollment = AuthPasswordEnrollment(passwordHash = PasswordHasher.hash("hunter2"), createdAt = TEST_NOW).apply { id = 1L }
            every { enrollmentRepository.findById(1L) } returns Optional.of(enrollment)

            then("it authenticates for that account") {
                val outcome = handler.patch(
                    toolSessionId, email = "max@example.com", password = "hunter2",
                    accountId = 42L, enrollmentRef = EnrollmentRef(PASSWORD_ENROLLMENT_TYPE, "1")
                )

                val authenticated = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                authenticated.subject shouldBe Subject.Account(42L)
                authenticated.amr shouldBe listOf("password")
            }
        }

        `when`("the form is read before anything is entered") {
            then("it carries the demo password, so the login form comes pre-filled like auth-password's") {
                val outcome = handler.read(toolSessionId)

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                outcome.demo?.get("password") shouldBe "Demo1234!"
            }
        }

        `when`("the email never resolved to anything (enumeration protection)") {
            then("it fails with the same constant-shape message, naming no account") {
                val outcome = handler.patch(toolSessionId, email = "unknown@example.com", password = "hunter2", accountId = null, enrollmentRef = null)

                outcome shouldBe ToolOutcome.Failed.LookupAuth(Text("E-Mail oder Passwort ungueltig"), attempted = null)
            }
        }
    }
})
