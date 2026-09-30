package com.example.identity.tools.auth_device.internal.authdevice
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_device.internal.DeviceEnrollment
import com.example.identity.tools.auth_device.internal.DeviceEnrollmentRepository

import com.example.identity.tools.auth_device.AuthDeviceDescriptor
import com.example.identity.tools.auth_device.DEVICE_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.device.DevicePublicKey
import com.example.identity.contract.tool_api.device.UserVerification
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
 * wiring only - the match decision is covered by [AuthDeviceFlowTest].
 */
class AuthDeviceToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthDeviceToolSessionRepository>()
    val enrollmentRepository = mockk<DeviceEnrollmentRepository>()
    val handler = AuthDeviceToolHandler(AuthDeviceDescriptor, toolDataRepository, enrollmentRepository, clock = TEST_CLOCK)
    val toolSessionId = ToolSessionId(UUID.randomUUID())

    given("start()") {
        `when`("the enrollment reference has the wrong type") {
            val result = runCatching { handler.start(toolSessionId, EnrollmentRef("auth_password.enrollment", "1")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("the enrollment reference id is not numeric") {
            val result = runCatching { handler.start(toolSessionId, EnrollmentRef(DEVICE_ENROLLMENT_TYPE, "not-a-number")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("the referenced auth_device.enrollment row does not exist") {
            every { enrollmentRepository.findById(1L) } returns Optional.empty()
            val result = runCatching { handler.start(toolSessionId, EnrollmentRef(DEVICE_ENROLLMENT_TYPE, "1")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("the referenced auth_device.enrollment row exists") {
            every { enrollmentRepository.findById(1L) } returns Optional.of(DeviceEnrollment(thumbprint = "thumb-1", createdAt = TEST_NOW).apply { id = 1L })
            every { toolDataRepository.save(any()) } answers { firstArg() }
            val outcome = handler.start(toolSessionId, EnrollmentRef(DEVICE_ENROLLMENT_TYPE, "1"))

            then("it asks for the device proof at step auth") {
                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                outcome.nextStep shouldBe "auth"
            }
        }
    }

    given("an active auth-device tool session bound to an enrollment") {
        val data = AuthDeviceToolSession(toolSessionId = toolSessionId, enrollmentRefId = "1", createdAt = TEST_NOW)
        every { toolDataRepository.findByToolSessionId(toolSessionId) } returns data
        every { enrollmentRepository.findById(1L) } returns Optional.of(DeviceEnrollment(thumbprint = "thumb-1", createdAt = TEST_NOW).apply { id = 1L })

        `when`("the presented device key's thumbprint matches the enrolled one") {
            val devicePublicKey = DevicePublicKey(kty = "EC", crv = "P-256", x = "x-coord", y = "y-coord", thumbprint = "thumb-1")
            val outcome = handler.patch(toolSessionId, devicePublicKey, UserVerification.BIOMETRIC)

            then("it authenticates at the descriptor's own maxAcr, BIOMETRIC mapped to POSSESSION+INHERENCE") {
                val authenticated = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                authenticated.amr shouldBe listOf("device", "biometric")
                authenticated.achievedAcr shouldBe AuthDeviceDescriptor.maxAcr
            }
        }

        `when`("the presented device key's thumbprint doesn't match the enrolled one") {
            val devicePublicKey = DevicePublicKey(kty = "EC", crv = "P-256", x = "other-x", y = "other-y", thumbprint = "thumb-other")
            val outcome = handler.patch(toolSessionId, devicePublicKey, UserVerification.PIN)

            then("it fails") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Geraet nicht erkannt"))
            }
        }
    }

    given("an auth-device tool session whose enrollment was removed meanwhile, on another channel") {
        val goneSessionId = ToolSessionId(UUID.randomUUID())
        every { toolDataRepository.findByToolSessionId(goneSessionId) } returns AuthDeviceToolSession(toolSessionId = goneSessionId, enrollmentRefId = "7", createdAt = TEST_NOW)
        every { enrollmentRepository.findById(7L) } returns Optional.empty()

        `when`("the device proof arrives") {
            val devicePublicKey = DevicePublicKey(kty = "EC", crv = "P-256", x = "x-coord", y = "y-coord", thumbprint = "thumb-1")
            val result = runCatching { handler.patch(goneSessionId, devicePublicKey, UserVerification.PIN) }

            then("it is an unresolvable reference, not a server error and not a wrong guess") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }
})
