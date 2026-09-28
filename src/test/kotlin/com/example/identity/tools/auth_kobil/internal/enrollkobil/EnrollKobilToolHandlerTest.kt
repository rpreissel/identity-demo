package com.example.identity.tools.auth_kobil.internal.enrollkobil

import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.simulation.kobil.KobilSsms
import com.example.identity.simulation.kobil.KobilUserRef
import com.example.identity.tools.auth_kobil.EnrollKobilDescriptor
import com.example.identity.tools.auth_kobil.KOBIL_BINDING_KEY_REF
import com.example.identity.tools.auth_kobil.KOBIL_DEVICE_ID
import com.example.identity.tools.auth_kobil.KOBIL_ENROLLMENT_TYPE
import com.example.identity.tools.auth_kobil.api.v1.KobilActivationStep
import com.example.identity.tools.auth_kobil.internal.KobilEnrollment
import com.example.identity.tools.auth_kobil.internal.KobilEnrollmentRepository
import com.example.identity.tools.auth_kobil.internal.KobilSecrets
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories and KOBIL mocked with MockK. Covers what start
 * mints and hands out, and what a confirmed activation writes; the decision itself is covered by
 * [EnrollKobilFlowTest].
 */
class EnrollKobilToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<EnrollKobilToolSessionRepository>()
    val enrollmentRepository = mockk<KobilEnrollmentRepository>()
    val secrets = KobilSecrets(pinLength = 8)
    val ssms = mockk<KobilSsms>()
    val tenantId = "identity-demo"
    val handler = EnrollKobilToolHandler(EnrollKobilDescriptor, toolDataRepository, enrollmentRepository, secrets, ssms, tenantId)

    /** A session mid-setup for KOBIL user [kobilUserId], still holding its minted secrets. */
    fun activating(kobilUserId: String): Pair<UUID, EnrollKobilToolSession> {
        val toolSessionId = UUID.randomUUID()
        val data = EnrollKobilToolSession(
            toolSessionId = toolSessionId,
            kobilTenantId = tenantId,
            kobilUserId = kobilUserId,
            activationCode = "ACT23456",
            pin = "12345678",
            unlockSecret = "unlock-secret-$kobilUserId",
        )
        every { toolDataRepository.findById(toolSessionId) } returns Optional.of(data)
        return toolSessionId to data
    }

    given("start()") {
        `when`("an enroll-kobil run begins") {
            val toolSessionId = UUID.randomUUID()
            val kobilUser = KobilUserRef(tenantId, "kob-new")
            every { ssms.provisionUser(tenantId, toolSessionId.toString()) } returns kobilUser
            every { ssms.issueActivationCode(kobilUser) } returns "ACT23456"
            val pinAtKobil = slot<String>()
            every { ssms.setPin(kobilUser, capture(pinAtKobil)) } just Runs
            val saved = slot<EnrollKobilToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }
            val outcome = handler.start(toolSessionId)

            then("it hands out everything the SDK's activation needs, at step activate") {
                val step = outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                step.nextStep shouldBe "activate"
                val activation = step.stepData.shouldBeInstanceOf<KobilActivationStep>()
                activation.missingFields shouldBe listOf("activated", "biometricConsent")
                activation.tenantId shouldBe tenantId
                activation.kobilUserId shouldBe "kob-new"
                activation.activationCode shouldBe "ACT23456"
                activation.pin shouldMatch Regex("\\d{8}")
            }

            then("the PIN handed out is the one set at KOBIL and kept in the session") {
                val activation = (outcome as ToolOutcome.InProgress).stepData as KobilActivationStep
                pinAtKobil.captured shouldBe activation.pin
                saved.captured.pin shouldBe activation.pin
                saved.captured.unlockSecret shouldBe activation.unlockSecret
            }
        }
    }

    given("a session whose device KOBIL has activated") {
        every { ssms.deviceOf(KobilUserRef(tenantId, "kob-a")) } returns "dev-a"
        every { ssms.deviceOf(KobilUserRef(tenantId, "kob-b")) } returns "dev-b"
        every { enrollmentRepository.findByKobilUserId(any()) } returns null
        val written = mutableListOf<KobilEnrollment>()
        every { enrollmentRepository.save(any()) } answers {
            firstArg<KobilEnrollment>().apply { id = 40L + written.size }.also { written += it }
        }

        `when`("the app confirms activation with biometric consent") {
            val (toolSessionId, data) = activating("kob-a")
            val outcome = handler.patch(toolSessionId, activated = true, biometricConsent = true, bindingKeyRef = "jkt-a", label = "Mein Handy")

            then("it enrolls with kobil and biometric, and reports binding key and device for later reads") {
                outcome shouldBe ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef(KOBIL_ENROLLMENT_TYPE, "40"),
                    amr = listOf("kobil", "biometric"),
                    achievedAcr = EnrollKobilDescriptor.maxAcr,
                    factorTypes = setOf(FactorType.POSSESSION, FactorType.INHERENCE),
                    instanceDetails = mapOf(KOBIL_BINDING_KEY_REF to "jkt-a", KOBIL_DEVICE_ID to "dev-a"),
                    label = "Mein Handy",
                )
            }

            then("the credential keeps the PIN and only the hash of the unlock secret") {
                val enrollment = written.single { it.kobilUserId == "kob-a" }
                enrollment.kobilDeviceId shouldBe "dev-a"
                enrollment.pin shouldBe "12345678"
                enrollment.unlockSecretHash shouldBe secrets.hash("unlock-secret-kob-a")
                enrollment.bindingKeyRef shouldBe "jkt-a"
            }

            then("the tool session forgets the activation secrets") {
                data.activationCode shouldBe ""
                data.pin shouldBe ""
                data.unlockSecret shouldBe ""
            }
        }

        `when`("the app confirms activation without biometric consent") {
            val (toolSessionId, _) = activating("kob-b")
            val outcome = handler.patch(toolSessionId, activated = true, biometricConsent = false, bindingKeyRef = "jkt-b", label = null)

            then("it enrolls with kobil and pin, possession plus knowledge") {
                val enrolled = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>()
                enrolled.amr shouldBe listOf("kobil", "pin")
                enrolled.factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
            }

            then("it stores no unlock-secret hash: that is the whole consent mechanism") {
                written.single { it.kobilUserId == "kob-b" }.unlockSecretHash shouldBe null
            }
        }
    }

    given("a session whose activation was already written once") {
        every { ssms.deviceOf(KobilUserRef(tenantId, "kob-again")) } returns "dev-again"
        val existing = KobilEnrollment(kobilTenantId = tenantId, kobilUserId = "kob-again", kobilDeviceId = "dev-again", pin = "12345678", bindingKeyRef = "jkt")
            .apply { id = 77L }
        every { enrollmentRepository.findByKobilUserId("kob-again") } returns existing

        `when`("the app confirms the same activation again") {
            val (toolSessionId, _) = activating("kob-again")
            val outcome = handler.patch(toolSessionId, activated = true, biometricConsent = false, bindingKeyRef = "jkt", label = null)

            then("it reuses the existing row instead of writing a second one") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Enrolled>().enrollmentRef shouldBe EnrollmentRef(KOBIL_ENROLLMENT_TYPE, "77")
                verify(exactly = 0) { enrollmentRepository.save(match<KobilEnrollment> { it.kobilUserId == "kob-again" }) }
            }
        }
    }

    given("a session whose device KOBIL does not know yet") {
        every { ssms.deviceOf(KobilUserRef(tenantId, "kob-pending")) } returns null

        `when`("the app claims it has activated") {
            val (toolSessionId, _) = activating("kob-pending")
            val outcome = handler.patch(toolSessionId, activated = true, biometricConsent = true, bindingKeyRef = "jkt", label = null)

            then("it stays at step activate and writes no credential, since the device id comes only from KOBIL") {
                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>().nextStep shouldBe "activate"
                verify(exactly = 0) { enrollmentRepository.save(match<KobilEnrollment> { it.kobilUserId == "kob-pending" }) }
            }
        }

        `when`("the app has not activated yet") {
            val (toolSessionId, _) = activating("kob-idle")
            val outcome = handler.patch(toolSessionId, activated = false, biometricConsent = null, bindingKeyRef = "jkt", label = null)

            then("it stays at step activate without asking KOBIL") {
                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>().nextStep shouldBe "activate"
                verify(exactly = 0) { ssms.deviceOf(KobilUserRef(tenantId, "kob-idle")) }
            }
        }
    }
})
