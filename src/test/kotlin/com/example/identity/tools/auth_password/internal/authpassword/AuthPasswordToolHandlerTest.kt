package com.example.identity.tools.auth_password.internal.authpassword
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_password.internal.PasswordHasher
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollment

import com.example.identity.tools.auth_password.AuthPasswordDescriptor
import com.example.identity.tools.auth_password.PASSWORD_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the input decision is covered by [AuthPasswordFlowTest].
 */
class AuthPasswordToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthPasswordToolSessionRepository>()
    val enrollmentRepository = mockk<AuthPasswordEnrollmentRepository>()
    val handler = AuthPasswordToolHandler(AuthPasswordDescriptor, toolDataRepository, enrollmentRepository, clock = TEST_CLOCK)
    val toolSessionId = UUID.randomUUID()

    given("start()") {
        `when`("the enrollment reference has the wrong type") {
            val result = runCatching { handler.start(toolSessionId, EnrollmentRef("auth_device.enrollment", "1")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("the referenced enrollment exists") {
            every { enrollmentRepository.existsById(1L) } returns true
            every { toolDataRepository.save(any()) } answers { firstArg() }
            val outcome = handler.start(toolSessionId, EnrollmentRef(PASSWORD_ENROLLMENT_TYPE, "1"))

            then("it asks for the password at step auth") {
                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                outcome.nextStep shouldBe "auth"
            }
        }
    }

    given("an active auth-password tool session bound to an enrollment") {
        val enrollment = AuthPasswordEnrollment(passwordHash = PasswordHasher.hash("hunter2"), createdAt = TEST_NOW).apply { id = 1L }
        val data = AuthPasswordToolSession(toolSessionId = toolSessionId, enrollmentRefId = "1", createdAt = TEST_NOW)
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)
        every { enrollmentRepository.findById(1L) } returns Optional.of(enrollment)

        `when`("submitting the correct password") {
            val outcome = handler.patch(toolSessionId, "hunter2")

            then("it authenticates at the descriptor's own maxAcr and factorTypes") {
                val authenticated = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                authenticated.amr shouldBe listOf("password")
                authenticated.achievedAcr shouldBe AuthPasswordDescriptor.maxAcr
            }
        }

        `when`("submitting the wrong password") {
            val outcome = handler.patch(toolSessionId, "wrong")

            then("it fails") {
                outcome shouldBe ToolOutcome.Failed.IdentifiedAuth(Text("Passwort ungueltig"))
            }
        }
    }

    given("an auth-password tool session whose enrollment was removed meanwhile, on another channel") {
        val goneSessionId = UUID.randomUUID()
        every { toolDataRepository.findById(goneSessionId) } returns Optional.of(AuthPasswordToolSession(toolSessionId = goneSessionId, enrollmentRefId = "7", createdAt = TEST_NOW))
        every { enrollmentRepository.findById(7L) } returns Optional.empty()

        `when`("a password arrives") {
            val result = runCatching { handler.patch(goneSessionId, "hunter2") }

            then("it is an unresolvable reference, not a failed attempt that would count against the account") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }
})
