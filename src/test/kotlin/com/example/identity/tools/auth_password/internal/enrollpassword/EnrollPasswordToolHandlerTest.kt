package com.example.identity.tools.auth_password.internal.enrollpassword
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollmentRepository
import com.example.identity.tools.auth_password.internal.AuthPasswordEnrollment

import com.example.identity.tools.auth_password.EnrollPasswordDescriptor
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
 * wiring only - the decision branches (too short, nothing submitted) are covered by
 * [EnrollPasswordFlowTest].
 */
class EnrollPasswordToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<EnrollPasswordToolSessionRepository>()
    val enrollmentRepository = mockk<AuthPasswordEnrollmentRepository>()
    val handler = EnrollPasswordToolHandler(EnrollPasswordDescriptor, toolDataRepository, enrollmentRepository)
    val toolSessionId = UUID.randomUUID()

    given("an active enroll-password tool session") {
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(EnrollPasswordToolSession(toolSessionId = toolSessionId))

        `when`("submitting a password meeting the minimum length") {
            every { enrollmentRepository.save(any()) } answers { firstArg<AuthPasswordEnrollment>().apply { id = 7L } }

            then("it enrolls immediately - no confirmation handshake needed") {
                val outcome = handler.patch(toolSessionId, password = "correct-horse-battery")

                val enrolled = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>()
                enrolled.enrollmentRef.type shouldBe "auth_password.enrollment"
                enrolled.enrollmentRef.id shouldBe "7"
                enrolled.amr shouldBe listOf("password")
                enrolled.achievedAcr shouldBe EnrollPasswordDescriptor.maxAcr
                enrolled.factorTypes shouldBe EnrollPasswordDescriptor.factorTypes
            }
        }
    }
})
