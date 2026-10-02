package com.example.identity.tools.auth_qr.internal.enrollqr

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.tools.auth_qr.EnrollQrDescriptor
import com.example.identity.tools.auth_qr.internal.QrOptIn
import com.example.identity.tools.auth_qr.internal.QrOptInRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID

/** One active tool session; a saved opt-in gets id 5. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val saved = slot<EnrollQrToolSession>()
    val sessions = mockk<EnrollQrToolSessionRepository>().also {
        every { it.save(capture(saved)) } answers { saved.captured }
        every { it.findByToolSessionId(any()) } returns null
        every { it.findByToolSessionId(toolSessionId) } returns EnrollQrToolSession(toolSessionId = toolSessionId, createdAt = TEST_NOW)
    }
    val optIns = mockk<QrOptInRepository>().also {
        every { it.save(any()) } answers { firstArg<QrOptIn>().apply { id = 5L } }
    }
    val handler = EnrollQrToolHandler(EnrollQrDescriptor, sessions, optIns, clock = TEST_CLOCK)
}

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. enroll-qr is a pure opt-in:
 * the PATCH itself is the confirmation and writes a marker row, no secret.
 */
class EnrollQrToolHandlerTest : BehaviorSpec({

    given("no enroll-qr tool session yet") {
        val f = Fixture()

        `when`("an enroll-qr run begins") {
            val toolSessionId = ToolSessionId(UUID.randomUUID())
            val outcome = f.handler.start(toolSessionId)

            then("it records the run and waits at the descriptor's start step") {
                f.saved.captured.toolSessionId shouldBe toolSessionId
                outcome shouldBe ToolOutcome.InProgress(nextStep = EnrollQrDescriptor.startStep)
            }
        }
    }

    given("an active enroll-qr tool session") {
        val f = Fixture()

        `when`("the user confirms") {
            val outcome = f.handler.patch(f.toolSessionId)

            then("it writes the opt-in marker and enrolls it, with no amr and no factor") {
                outcome shouldBe ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef("auth_qr.enrollment", "5"),
                    amr = emptyList(),
                    achievedAcr = EnrollQrDescriptor.maxAcr,
                    factorTypes = emptySet(),
                )
            }
        }
    }

    given("an unknown enroll-qr tool session") {
        val f = Fixture()

        `when`("a confirmation arrives for it") {
            val result = runCatching { f.handler.patch(ToolSessionId(UUID.randomUUID())) }

            then("it fails as a programming error") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }

            then("it writes no opt-in") {
                verify(exactly = 0) { f.optIns.save(any<QrOptIn>()) }
            }
        }
    }
})
