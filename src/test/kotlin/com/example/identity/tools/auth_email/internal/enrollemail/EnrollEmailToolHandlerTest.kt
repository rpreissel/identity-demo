package com.example.identity.tools.auth_email.internal.enrollemail

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.directory.EMAIL_ANCHOR_ENROLLMENT
import com.example.identity.tools.auth_email.EnrollEmailDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.UUID

/**
 * Pure unit test: no Spring context, the repository mocked with MockK. enroll-email is a one-shot,
 * so start and every later read return the same Enrolled outcome referencing the EMAIL anchor.
 */
class EnrollEmailToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<EnrollEmailToolSessionRepository>()
    val handler = EnrollEmailToolHandler(EnrollEmailDescriptor, toolDataRepository, clock = TEST_CLOCK)

    // No amr: control of the address was proven by confirm-email, not in this run.
    val expected = ToolOutcome.Completed.Enrolled(
        enrollmentRef = EMAIL_ANCHOR_ENROLLMENT,
        amr = emptyList(),
        achievedAcr = EnrollEmailDescriptor.maxAcr,
        factorTypes = EnrollEmailDescriptor.factorTypes
    )

    given("start()") {
        `when`("an enroll-email run begins") {
            val toolSessionId = ToolSessionId(UUID.randomUUID())
            val saved = slot<EnrollEmailToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }
            val outcome = handler.start(toolSessionId)

            then("it records the run and completes at once with the EMAIL anchor as credential") {
                saved.captured.toolSessionId shouldBe toolSessionId
                outcome shouldBe expected
            }
        }
    }

    given("a completed enroll-email tool session") {
        val toolSessionId = ToolSessionId(UUID.randomUUID())
        every { toolDataRepository.findByToolSessionId(toolSessionId) } returns EnrollEmailToolSession(toolSessionId = toolSessionId, createdAt = TEST_NOW)

        `when`("it is read again") {
            val outcome = handler.read(toolSessionId)

            then("it returns the same Enrolled outcome") {
                outcome shouldBe expected
            }
        }
    }

    given("no enroll-email tool session") {
        val unknownId = ToolSessionId(UUID.randomUUID())
        every { toolDataRepository.findByToolSessionId(unknownId) } returns null

        `when`("it is read") {
            val result = runCatching { handler.read(unknownId) }

            then("it fails as a programming error, not with a made-up outcome") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }
})
