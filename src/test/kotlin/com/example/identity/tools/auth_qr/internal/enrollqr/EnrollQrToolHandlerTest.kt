package com.example.identity.tools.auth_qr.internal.enrollqr

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
import java.time.Clock
import java.util.Optional
import java.util.UUID
import java.time.Instant

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. enroll-qr is a pure opt-in:
 * the PATCH itself is the confirmation and writes a marker row, no secret.
 */
class EnrollQrToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<EnrollQrToolSessionRepository>()
    val qrOptInRepository = mockk<QrOptInRepository>()
    val handler = EnrollQrToolHandler(EnrollQrDescriptor, toolDataRepository, qrOptInRepository, clock = Clock.systemUTC())

    given("start()") {
        `when`("an enroll-qr run begins") {
            val toolSessionId = UUID.randomUUID()
            val saved = slot<EnrollQrToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }
            val outcome = handler.start(toolSessionId)

            then("it records the run and waits at the descriptor's start step") {
                saved.captured.toolSessionId shouldBe toolSessionId
                outcome shouldBe ToolOutcome.InProgress(nextStep = EnrollQrDescriptor.startStep)
            }
        }
    }

    given("an active enroll-qr tool session") {
        val toolSessionId = UUID.randomUUID()
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(EnrollQrToolSession(toolSessionId = toolSessionId, createdAt = Instant.now()))
        every { qrOptInRepository.save(any()) } answers { firstArg<QrOptIn>().apply { id = 5L } }

        `when`("the user confirms") {
            val outcome = handler.patch(toolSessionId)

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

    given("no enroll-qr tool session") {
        val unknownId = UUID.randomUUID()
        every { toolDataRepository.findById(unknownId) } returns Optional.empty()
        // Its own opt-in repository, so no earlier confirmation counts against the check below.
        val untouchedOptIns = mockk<QrOptInRepository>()
        val isolatedHandler = EnrollQrToolHandler(EnrollQrDescriptor, toolDataRepository, untouchedOptIns, clock = Clock.systemUTC())

        `when`("a confirmation arrives") {
            val result = runCatching { isolatedHandler.patch(unknownId) }

            then("it fails as a programming error and writes no opt-in") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
                verify(exactly = 0) { untouchedOptIns.save(any<QrOptIn>()) }
            }
        }
    }
})
