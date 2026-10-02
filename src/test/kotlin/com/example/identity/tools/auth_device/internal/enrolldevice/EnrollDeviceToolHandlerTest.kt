package com.example.identity.tools.auth_device.internal.enrolldevice
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.tools.auth_device.internal.DeviceEnrollment
import com.example.identity.tools.auth_device.internal.DeviceEnrollmentRepository

import com.example.identity.tools.auth_device.DEVICE_BINDING_KEY_REF
import com.example.identity.tools.auth_device.DEVICE_ENROLLMENT_TYPE
import com.example.identity.tools.auth_device.EnrollDeviceDescriptor
import com.example.identity.contract.tool_api.device.DevicePublicKey
import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

private val DEVICE_KEY = DevicePublicKey(kty = "EC", crv = "P-256", x = "x-coord", y = "y-coord", thumbprint = "thumb-1")

/** One active tool session; no device is enrolled until a test adds one. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = mockk<EnrollDeviceToolSessionRepository>().also {
        every { it.save(any()) } answers { firstArg() }
        every { it.findByToolSessionId(toolSessionId) } returns EnrollDeviceToolSession(toolSessionId = toolSessionId, createdAt = TEST_NOW)
    }
    val enrollments = mockk<DeviceEnrollmentRepository>().also {
        every { it.findByThumbprint(any()) } returns null
        every { it.save(any()) } answers { firstArg<DeviceEnrollment>().apply { id = 9L } }
    }
    val handler = EnrollDeviceToolHandler(EnrollDeviceDescriptor, sessions, enrollments, clock = TEST_CLOCK)

    fun withEnrolledDevice(id: Long) = apply {
        every { enrollments.findByThumbprint(DEVICE_KEY.thumbprint) } returns DeviceEnrollment(thumbprint = DEVICE_KEY.thumbprint, createdAt = TEST_NOW).apply { this.id = id }
    }
}

/**
 * Unit test of the handler's persistence and its idempotent reuse by thumbprint. [EnrollDeviceFlow]
 * has no decision branches: the proof arrives verified, so it always enrolls.
 */
class EnrollDeviceToolHandlerTest : BehaviorSpec({

    given("no enroll-device tool session yet") {
        val f = Fixture()

        `when`("a tool session starts") {
            val outcome = f.handler.start(ToolSessionId(UUID.randomUUID()))

            then("it asks for the device proof at step enroll, without stepData") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "enroll", stepData = null)
            }
        }
    }

    given("an active enroll-device tool session and no device with this thumbprint") {
        val f = Fixture()

        `when`("the device proof arrives with PIN verification") {
            val outcome = f.handler.patch(f.toolSessionId, DEVICE_KEY, UserVerification.PIN, "binding-key-1", "Handy")

            then("it enrolls a new auth_device.enrollment row, with PIN mapped to POSSESSION+KNOWLEDGE") {
                val enrolled = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>()
                enrolled.enrollmentRef.type shouldBe DEVICE_ENROLLMENT_TYPE
                enrolled.enrollmentRef.id shouldBe "9"
                enrolled.amr shouldBe listOf("device", "pin")
                enrolled.achievedAcr shouldBe EnrollDeviceDescriptor.maxAcr
                enrolled.factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
                enrolled.instanceDetails shouldBe mapOf(DEVICE_BINDING_KEY_REF to "binding-key-1")
                enrolled.label shouldBe "Handy"
            }
        }
    }

    given("an active enroll-device tool session and this exact thumbprint already enrolled") {
        val f = Fixture().withEnrolledDevice(3L)

        `when`("the same physical key is enrolled again, with BIOMETRIC verification") {
            val outcome = f.handler.patch(f.toolSessionId, DEVICE_KEY, UserVerification.BIOMETRIC, "binding-key-1", null)

            then("the existing row is reused, with BIOMETRIC mapped to POSSESSION+INHERENCE") {
                val enrolled = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>()
                enrolled.enrollmentRef.id shouldBe "3"
                enrolled.factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.INHERENCE)
            }

            then("no second row is inserted") {
                verify(exactly = 0) { f.enrollments.save(any()) }
            }
        }
    }
})
