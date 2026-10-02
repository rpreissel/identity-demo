package com.example.identity.tools.auth_password.internal.enrollpassword
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollment
import com.example.identity.tools.auth_password.internal.PasswordHasher

import com.example.identity.tools.auth_password.DEMO_PASSWORD
import com.example.identity.tools.auth_password.EnrollPasswordDescriptor
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.PASSWORD_EXISTS_MARKER
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.UUID

/** One active tool session; a saved enrollment gets id 7. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = mockk<EnrollPasswordToolSessionRepository>().also {
        every { it.save(any()) } answers { firstArg() }
        every { it.findByToolSessionId(toolSessionId) } returns EnrollPasswordToolSession(toolSessionId = toolSessionId, createdAt = TEST_NOW)
    }
    val saved = slot<AuthPasswordEnrollment>()
    val enrollments = mockk<AuthPasswordEnrollmentRepository>().also {
        every { it.save(capture(saved)) } answers { saved.captured.apply { id = 7L } }
    }
    val handler = EnrollPasswordToolHandler(EnrollPasswordDescriptor, sessions, enrollments, clock = TEST_CLOCK)
}

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the decision branches (too short, nothing submitted) are covered by
 * [EnrollPasswordFlowTest].
 */
class EnrollPasswordToolHandlerTest : BehaviorSpec({

    given("no enroll-password tool session yet") {
        val f = Fixture()

        `when`("a tool session starts") {
            val outcome = f.handler.start(ToolSessionId(UUID.randomUUID()))

            then("it asks for the password at step enroll, offering the demo password") {
                outcome shouldBe ToolOutcome.InProgress(
                    nextStep = "enroll",
                    stepData = MissingFields(listOf("password")),
                    demo = mapOf("password" to DEMO_PASSWORD),
                )
            }
        }
    }

    given("an active enroll-password tool session") {
        val f = Fixture()

        `when`("submitting a password meeting the minimum length") {
            val outcome = f.handler.patch(f.toolSessionId, password = "correct-horse-battery")

            then("it enrolls immediately - no confirmation handshake needed - and states that a password exists") {
                outcome shouldBe ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef("auth_password.enrollment", "7"),
                    amr = listOf("password"),
                    achievedAcr = EnrollPasswordDescriptor.maxAcr,
                    factorTypes = EnrollPasswordDescriptor.factorTypes,
                    claims = listOf(
                        Claim(AttributeType.PASSWORD_EXISTS, PASSWORD_EXISTS_MARKER, ClaimSource(EnrollPasswordDescriptor.toolId.value), EnrollPasswordDescriptor.maxAcr)
                    ),
                )
            }

            then("it stores a hash of the password, not the password") {
                PasswordHasher.matches("correct-horse-battery", f.saved.captured.passwordHash) shouldBe true
            }
        }
    }
})
