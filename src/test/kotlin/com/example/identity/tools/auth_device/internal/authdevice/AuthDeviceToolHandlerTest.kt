package com.example.identity.tools.auth_device.internal.authdevice
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_device.internal.DeviceEnrollment
import com.example.identity.tools.auth_device.internal.DeviceEnrollmentRepository

import com.example.identity.tools.auth_device.internal.DEVICE_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.device.DevicePublicKey
import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Optional
import java.util.UUID

private val ENROLLED_KEY = DevicePublicKey(kty = "EC", crv = "P-256", x = "x-coord", y = "y-coord", thumbprint = "thumb-1")

/** No device is enrolled and no tool session exists until a test adds them. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = mockk<AuthDeviceToolSessionRepository>().also {
        every { it.findByToolSessionId(any()) } returns null
        every { it.save(any()) } answers { firstArg() }
    }
    val enrollments = mockk<DeviceEnrollmentRepository>().also {
        every { it.findById(any()) } returns Optional.empty()
    }
    val handler = AuthDeviceToolHandler( sessions, enrollments, clock = TEST_CLOCK)

    fun withEnrolledDevice(id: Long) = apply {
        every { enrollments.findById(id) } returns Optional.of(DeviceEnrollment(thumbprint = ENROLLED_KEY.thumbprint, createdAt = TEST_NOW).apply { this.id = id })
    }

    fun withSessionBoundTo(enrollmentRefId: String) = apply {
        every { sessions.findByToolSessionId(toolSessionId) } returns
            AuthDeviceToolSession(toolSessionId = toolSessionId, enrollmentRefId = enrollmentRefId, createdAt = TEST_NOW)
    }
}

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the match decision is covered by [AuthDeviceFlowTest].
 */
class AuthDeviceToolHandlerTest : BehaviorSpec({

    given("no enrolled device") {
        val f = Fixture()

        `when`("a tool session starts with an enrollment reference of the wrong type") {
            val result = runCatching { f.handler.start(f.toolSessionId, EnrollmentRef("auth_password.enrollment", "1")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("a tool session starts with a non-numeric enrollment reference id") {
            val result = runCatching { f.handler.start(f.toolSessionId, EnrollmentRef(DEVICE_ENROLLMENT_TYPE, "not-a-number")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("a tool session starts with a reference to a missing auth_device.enrollment row") {
            val result = runCatching { f.handler.start(f.toolSessionId, EnrollmentRef(DEVICE_ENROLLMENT_TYPE, "1")) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }

    given("an enrolled device") {
        val f = Fixture().withEnrolledDevice(1L)

        `when`("a tool session starts with a reference to it") {
            val outcome = f.handler.start(f.toolSessionId, EnrollmentRef(DEVICE_ENROLLMENT_TYPE, "1"))

            then("it asks for the device proof at step auth, without stepData") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "auth", stepData = null)
            }

            then("it binds the tool session to the enrollment") {
                verify { f.sessions.save(match { it.toolSessionId == f.toolSessionId && it.enrollmentRefId == "1" }) }
            }
        }
    }

    given("an active auth-device tool session bound to an enrolled device") {
        val f = Fixture().withEnrolledDevice(1L).withSessionBoundTo("1")

        `when`("the presented device key's thumbprint matches the enrolled one") {
            val outcome = f.handler.patch(f.toolSessionId, ENROLLED_KEY, UserVerification.BIOMETRIC)

            then("it authenticates at its tool's own level, BIOMETRIC mapped to POSSESSION+INHERENCE") {
                val authenticated = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                authenticated.amr shouldBe listOf("device", "biometric")
                authenticated.achievedAcr shouldBe null
                authenticated.factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.INHERENCE)
            }
        }

        `when`("the presented device key's thumbprint doesn't match the enrolled one") {
            val otherKey = DevicePublicKey(kty = "EC", crv = "P-256", x = "other-x", y = "other-y", thumbprint = "thumb-other")
            val outcome = f.handler.patch(f.toolSessionId, otherKey, UserVerification.PIN)

            then("it fails") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Geraet nicht erkannt"))
            }
        }
    }

    given("an auth-device tool session whose enrollment was removed meanwhile, on another channel") {
        val f = Fixture().withSessionBoundTo("7")

        `when`("the device proof arrives") {
            val result = runCatching { f.handler.patch(f.toolSessionId, ENROLLED_KEY, UserVerification.PIN) }

            then("it is an unresolvable reference, not a server error and not a wrong guess") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }
})
