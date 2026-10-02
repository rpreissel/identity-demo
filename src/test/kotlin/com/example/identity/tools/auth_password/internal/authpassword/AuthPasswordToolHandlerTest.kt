package com.example.identity.tools.auth_password.internal.authpassword
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.TEST_CLOCK
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
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/** No password enrollment and no tool session exist until a test adds them. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = mockk<AuthPasswordToolSessionRepository>().also {
        every { it.save(any()) } answers { firstArg() }
    }
    val enrollments = mockk<AuthPasswordEnrollmentRepository>().also {
        every { it.existsById(any()) } returns false
        every { it.findById(any()) } returns Optional.empty()
    }
    val handler = AuthPasswordToolHandler( sessions, enrollments, clock = TEST_CLOCK)

    fun withEnrolledPassword(id: Long, password: String) = apply {
        every { enrollments.existsById(id) } returns true
        every { enrollments.findById(id) } returns
            Optional.of(AuthPasswordEnrollment(passwordHash = PasswordHasher.hash(password), createdAt = TEST_NOW).apply { this.id = id })
    }

    fun withSessionBoundTo(enrollmentRefId: String) = apply {
        every { sessions.findByToolSessionId(toolSessionId) } returns
            AuthPasswordToolSession(toolSessionId = toolSessionId, enrollmentRefId = enrollmentRefId, createdAt = TEST_NOW)
    }
}

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the input decision is covered by [AuthPasswordFlowTest].
 */
class AuthPasswordToolHandlerTest : BehaviorSpec({

    given("no password enrollment") {
        val f = Fixture()

        `when`("a tool session starts with an enrollment reference of the wrong type") {
            val result = runCatching { f.handler.start(f.toolSessionId, EnrollmentRef("auth_device.enrollment", "1")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }

    given("an enrolled password") {
        val f = Fixture().withEnrolledPassword(1L, "hunter2")

        `when`("a tool session starts with a reference to it") {
            val outcome = f.handler.start(f.toolSessionId, EnrollmentRef(PASSWORD_ENROLLMENT_TYPE, "1"))

            then("it asks for the password at step auth, offering the demo password") {
                outcome shouldBe ToolOutcome.InProgress(
                    nextStep = "auth",
                    stepData = MissingFields(listOf("password")),
                    demo = mapOf("password" to DEMO_PASSWORD),
                )
            }
        }
    }

    given("an active auth-password tool session bound to an enrolled password") {
        val f = Fixture().withEnrolledPassword(1L, "hunter2").withSessionBoundTo("1")

        `when`("submitting the correct password") {
            val outcome = f.handler.patch(f.toolSessionId, "hunter2")

            then("it authenticates at its tool's own level and factors") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                )
            }
        }

        `when`("submitting the wrong password") {
            val outcome = f.handler.patch(f.toolSessionId, "wrong")

            then("it fails") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Passwort ungueltig"))
            }
        }
    }

    given("an auth-password tool session whose enrollment was removed meanwhile, on another channel") {
        val f = Fixture().withSessionBoundTo("7")

        `when`("a password arrives") {
            val result = runCatching { f.handler.patch(f.toolSessionId, "hunter2") }

            then("it is an unresolvable reference, not a failed attempt that would count against the account") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }
})
