package com.example.identity.tools.auth_kobil.internal.authkobil

import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.tools.auth_kobil.internal.KobilPins
import com.example.identity.core.account.application.ClaimCryptoFixture
import com.example.identity.contract.tool_api.load
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.credentials.PasswordCredentialPort
import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.simulation.kobil.KobilOtpVerification
import com.example.identity.simulation.kobil.KobilRisk
import com.example.identity.simulation.kobil.KobilSsms
import com.example.identity.simulation.kobil.KobilUserRef
import com.example.identity.tools.auth_kobil.internal.KOBIL_ENROLLMENT_TYPE
import com.example.identity.tools.auth_kobil.api.v1.KobilOtpStep
import com.example.identity.tools.auth_kobil.api.v1.KobilUnlockCredential
import com.example.identity.tools.auth_kobil.api.v1.KobilUnlockStep
import com.example.identity.tools.auth_kobil.internal.KobilEnrollment
import com.example.identity.tools.auth_kobil.internal.KobilEnrollmentRepository
import com.example.identity.tools.auth_kobil.internal.KobilSecrets
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, the session data kept in memory, repositories, KOBIL and the
 * password port mocked with MockK.
 * Covers the unlock options, the PIN release and the outcome wiring; the redemption decision itself
 * is covered by [AuthKobilFlowTest].
 */
class AuthKobilToolHandlerTest : BehaviorSpec({

    val sessions = InMemoryToolSessionData()
    val enrollmentRepository = mockk<KobilEnrollmentRepository>()
    val secrets = KobilSecrets(pinLength = 8)
    val ssms = mockk<KobilSsms>()
    val passwordCredentials = mockk<PasswordCredentialPort>()
    val keys = ClaimCryptoFixture()
    val keyId = keys.newKey()
    val pins = KobilPins(keys.sealing)
    val handler = AuthKobilToolHandler(sessions, enrollmentRepository, secrets, ssms, passwordCredentials, pins,
        blockingRisks = setOf(KobilRisk.ROOTED, KobilRisk.EMULATOR, KobilRisk.DEBUGGER_ATTACHED, KobilRisk.APP_TAMPERED),
        pinReleaseTtlSeconds = 120,
        clock = TEST_CLOCK,)
    val tenantId = "identity-demo"

    /** An enrollment on device `dev-<id>`; [biometricConsent] decides whether an unlock-secret hash is stored. */
    fun enrollment(enrollmentId: Long, biometricConsent: Boolean): KobilEnrollment {
        val enrollment = KobilEnrollment(
            kobilTenantId = tenantId,
            kobilUserId = "kob-$enrollmentId",
            kobilDeviceId = "dev-$enrollmentId",
            sealedPin = pins.seal("12345678", keyId),
            masterKeyId = keyId,
            unlockSecretHash = if (biometricConsent) secrets.hash("unlock-secret-$enrollmentId") else null,
            bindingKeyRef = "jkt-$enrollmentId",
            createdAt = TEST_NOW,
        ).apply { id = enrollmentId }
        every { enrollmentRepository.findById(enrollmentId) } returns Optional.of(enrollment)
        return enrollment
    }

    /** A session on [enrollmentId], optionally with a PIN release by [release] that ends at [releaseEndsAt]. */
    fun session(enrollmentId: Long, release: UserVerification? = null, releaseEndsAt: Instant = TEST_NOW.plusSeconds(60)): ToolSessionId {
        val toolSessionId = ToolSessionId(UUID.randomUUID())
        val data = AuthKobilToolSession(enrollmentRefId = enrollmentId.toString())
        sessions.save(toolSessionId, release?.let { data.released(it, releaseEndsAt) } ?: data)
        return toolSessionId
    }

    /** The session as the handler last saved it. */
    fun stored(toolSessionId: ToolSessionId): AuthKobilToolSession = sessions.stored(toolSessionId)

    given("an enrollment with biometric consent, on an account that still has a password") {
        enrollment(1L, biometricConsent = true)

        `when`("a tool session starts with a reference to it") {
            val started = ToolSessionId(UUID.randomUUID())
            val outcome = handler.start(started, EnrollmentRef(KOBIL_ENROLLMENT_TYPE, "1"), passwordAvailable = true)
            val saved = stored(started)

            then("it stores the enrollment reference and offers both unlock ways at step unlock") {
                saved.enrollmentRefId shouldBe "1"
                saved.userVerification shouldBe null
                outcome shouldBe ToolOutcome.InProgress(
                    nextStep = "unlock",
                    stepData = KobilUnlockStep(listOf("biometric", "password"), tenantId, "kob-1"),
                )
            }
        }
    }

    given("an enrollment without biometric consent, on an account without a password") {
        enrollment(2L, biometricConsent = false)

        `when`("a tool session starts with a reference to it") {
            val outcome = handler.start(ToolSessionId(UUID.randomUUID()), EnrollmentRef(KOBIL_ENROLLMENT_TYPE, "2"), passwordAvailable = false)

            then("it offers no unlock way at all, and the client can say so") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "unlock", stepData = KobilUnlockStep(emptyList(), tenantId, "kob-2"))
            }
        }
    }

    given("no enrollment with id 99") {
        every { enrollmentRepository.findById(99L) } returns Optional.empty()

        `when`("a tool session starts with a reference to it") {
            val result = runCatching { handler.start(ToolSessionId(UUID.randomUUID()), EnrollmentRef(KOBIL_ENROLLMENT_TYPE, "99"), passwordAvailable = true) }

            then("it is an unresolvable reference (422), as in auth-sms") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }

    given("a kobil enrollment 3, and a reference of another tool's type with the same id") {
        enrollment(3L, biometricConsent = true)
        val smsRef = EnrollmentRef("auth_sms.enrollment", "3")

        `when`("a tool session starts with that reference") {
            val started = ToolSessionId(UUID.randomUUID())
            val result = runCatching { handler.start(started, smsRef, passwordAvailable = true) }

            then("it is an unresolvable reference (422), as in auth-sms") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
            then("no tool session is stored for it") {
                sessions.load<AuthKobilToolSession>(started) shouldBe null
            }
        }
    }

    given("an enrollment with biometric consent, awaiting its unlock") {
        val enrollment = enrollment(10L, biometricConsent = true)

        `when`("the app presents the right unlock secret") {
            val toolSessionId = session(10L)
            val outcome = handler.releasePin(toolSessionId, KobilUnlockCredential.BiometricUnlock("unlock-secret-10"), passwordEnrollment = null)

            then("it hands the PIN over in this response, at step otp") {
                outcome shouldBe ToolOutcome.InProgress(
                    nextStep = "otp",
                    stepData = KobilOtpStep(listOf("otp"), tenantId, "kob-10", kobilPin = "12345678"),
                )
            }

            then("it records a biometric release that ends after the configured 120 seconds") {
                stored(toolSessionId).userVerification shouldBe "biometric"
                stored(toolSessionId).pinReleaseExpiresAt shouldBe TEST_NOW.plusSeconds(120)
            }
        }

        `when`("the app presents a wrong unlock secret") {
            val toolSessionId = session(10L)
            val outcome = handler.releasePin(toolSessionId, KobilUnlockCredential.BiometricUnlock("guessed"), passwordEnrollment = null)

            then("it fails without releasing anything") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Entsperren fehlgeschlagen"))
                stored(toolSessionId).userVerification shouldBe null
                stored(toolSessionId).pinReleaseExpiresAt shouldBe null
            }
        }

        `when`("the app presents the right account password") {
            val passwordRef = EnrollmentRef("auth_password.enrollment", "5")
            every { passwordCredentials.verify(passwordRef, "hunter2") } returns true
            val toolSessionId = session(10L)
            val outcome = handler.releasePin(toolSessionId, KobilUnlockCredential.PasswordUnlock("hunter2"), passwordRef)

            then("it releases the PIN, recorded as a pin unlock rather than a password login") {
                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>().nextStep shouldBe "otp"
                stored(toolSessionId).userVerification shouldBe "pin"
            }
        }

        `when`("the account has no password but the app sends one") {
            every { passwordCredentials.verify(null, "hunter2") } returns false
            val toolSessionId = session(10L)
            val outcome = handler.releasePin(toolSessionId, KobilUnlockCredential.PasswordUnlock("hunter2"), passwordEnrollment = null)

            then("it still runs the password check, then fails with the one wording") {
                verify { passwordCredentials.verify(null, "hunter2") }
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Entsperren fehlgeschlagen"))
                stored(toolSessionId).userVerification shouldBe null
            }
        }
    }

    given("an enrollment without biometric consent, awaiting its unlock") {
        enrollment(11L, biometricConsent = false)

        `when`("the app presents an unlock secret anyway") {
            val toolSessionId = session(11L)
            val outcome = handler.releasePin(toolSessionId, KobilUnlockCredential.BiometricUnlock("unlock-secret-11"), passwordEnrollment = null)

            then("it fails with the same wording as a wrong secret") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Entsperren fehlgeschlagen"))
                stored(toolSessionId).userVerification shouldBe null
            }
        }
    }

    given("enrollment 20 on device dev-20, and KOBIL answering for several one-time passwords") {
        enrollment(20L, biometricConsent = true)
        val kobilUser = KobilUserRef(tenantId, "kob-20")
        every { ssms.verifyOtp(kobilUser, "otp-clean") } returns KobilOtpVerification("dev-20", setOf(KobilRisk.OS_OUTDATED))
        every { ssms.verifyOtp(kobilUser, "otp-unknown") } returns null
        every { ssms.verifyOtp(kobilUser, "otp-other-device") } returns KobilOtpVerification("dev-other", emptySet())
        every { ssms.verifyOtp(kobilUser, "otp-rooted") } returns KobilOtpVerification("dev-20", setOf(KobilRisk.ROOTED))

        `when`("an OTP arrives before any PIN release") {
            val toolSessionId = session(20L)
            val outcome = handler.patch(toolSessionId, "otp-never-redeemed")

            then("it fails as not unlocked and does not redeem the OTP at KOBIL") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Entsperren erforderlich"))
                verify(exactly = 0) { ssms.verifyOtp(any(), "otp-never-redeemed") }
            }
        }

        `when`("an OTP arrives after the PIN release has run out") {
            val toolSessionId = session(20L, UserVerification.BIOMETRIC, releaseEndsAt = TEST_NOW.minusSeconds(1))
            val outcome = handler.patch(toolSessionId, "otp-after-expiry")

            then("it fails as not unlocked and does not redeem the OTP at KOBIL") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Entsperren erforderlich"))
                verify(exactly = 0) { ssms.verifyOtp(any(), "otp-after-expiry") }
            }
        }

        `when`("a biometric release is live and KOBIL confirms the enrolled device with only a non-blocking signal") {
            val toolSessionId = session(20L, UserVerification.BIOMETRIC)
            val outcome = handler.patch(toolSessionId, "otp-clean")

            then("it authenticates with kobil and biometric, possession plus inherence") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                    amr = listOf("kobil", "biometric"),
                    factorTypes = setOf(FactorType.POSSESSION, FactorType.INHERENCE),
                )
            }
        }

        `when`("a password release is live and KOBIL confirms the enrolled device") {
            val toolSessionId = session(20L, UserVerification.PIN)
            val outcome = handler.patch(toolSessionId, "otp-clean")

            then("it authenticates with kobil and pin, possession plus knowledge") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                    amr = listOf("kobil", "pin"),
                    factorTypes = setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE),
                )
            }
        }

        `when`("KOBIL does not know the OTP") {
            val toolSessionId = session(20L, UserVerification.BIOMETRIC)
            val outcome = handler.patch(toolSessionId, "otp-unknown")

            then("it fails as not recognized") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Bestaetigung nicht erkannt"))
            }
        }

        `when`("the assertion comes from another device") {
            val toolSessionId = session(20L, UserVerification.BIOMETRIC)
            val outcome = handler.patch(toolSessionId, "otp-other-device")

            then("it fails without naming the expected device") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Geraet nicht erkannt"))
            }
        }

        `when`("the enrolled device reports a blocking risk") {
            val toolSessionId = session(20L, UserVerification.BIOMETRIC)
            val outcome = handler.patch(toolSessionId, "otp-rooted")

            then("it fails with its own reason, not folded into not recognized") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Geraet als unsicher gemeldet"))
            }
        }
    }

    given("a tool session whose enrollment was removed meanwhile, on another channel") {
        every { enrollmentRepository.findById(30L) } returns Optional.empty()
        val awaitingUnlock = session(30L)
        val released = session(30L, UserVerification.BIOMETRIC)

        `when`("the app asks for the PIN release") {
            val result = runCatching { handler.releasePin(awaitingUnlock, KobilUnlockCredential.BiometricUnlock("unlock-secret-30"), passwordEnrollment = null) }

            then("it is an unresolvable reference, not a failed unlock") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("an OTP arrives on a live release") {
            val result = runCatching { handler.patch(released, "otp-clean") }

            then("it is an unresolvable reference, not a wrong guess") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }

    given("a tool session bound to a reference that is no enrollment id") {
        val toolSessionId = ToolSessionId(UUID.randomUUID())
        sessions.save(toolSessionId, AuthKobilToolSession(enrollmentRefId = "not-a-number"))

        `when`("the app asks for the PIN release") {
            val result = runCatching { handler.releasePin(toolSessionId, KobilUnlockCredential.BiometricUnlock("unlock-secret"), passwordEnrollment = null) }

            then("it is an unresolvable reference") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("an OTP arrives") {
            val result = runCatching { handler.patch(toolSessionId, "otp-clean") }

            then("it is an unresolvable reference") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }
})
