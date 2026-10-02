package com.example.identity.simulation.kobil

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.simulation.kobil.internal.SsmsAssertion
import com.example.identity.simulation.kobil.internal.SsmsAssertionRepository
import com.example.identity.simulation.kobil.internal.SsmsUser
import com.example.identity.simulation.kobil.internal.SsmsUserRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import java.util.Optional

private const val TENANT = "identity-demo"
private const val PIN = "12345678"

/** A user of [TENANT] with [PIN]; everything else only where a test needs it. */
private fun ssmsUser(
    userId: String = "kob-1",
    activationCode: String? = null,
    deviceId: String? = null,
    riskSignals: String = "",
) = SsmsUser(userId = userId, tenantId = TENANT, pin = PIN, activationCode = activationCode, deviceId = deviceId, riskSignals = riskSignals, createdAt = TEST_NOW)

/** KOBIL over mocked repositories that know [users]; filed assertions land in [store], keyed by OTP. */
private class Fixture(vararg users: SsmsUser) {
    val store = mutableMapOf<String, SsmsAssertion>()
    private val userRepository = mockk<SsmsUserRepository>(relaxed = true).also { repository ->
        every { repository.findById(any()) } answers { Optional.ofNullable(users.find { it.userId == firstArg<String>() }) }
    }
    private val assertionRepository = mockk<SsmsAssertionRepository>(relaxed = true).also { repository ->
        val saved = slot<SsmsAssertion>()
        every { repository.save(capture(saved)) } answers { store[saved.captured.otp] = saved.captured; saved.captured }
        every { repository.findById(any()) } answers { Optional.ofNullable(store[firstArg<String>()]) }
    }
    val ssms = KobilSsms(userRepository, assertionRepository, clock = TEST_CLOCK)
}

/**
 * The provider's own rules, with its repositories mocked - the properties that make the OTP detour
 * worth anything: an activation code is spent once, a wrong PIN produces no assertion, and a
 * one-time password is redeemable exactly once.
 */
class KobilSsmsTest : BehaviorSpec({

    val ref = KobilUserRef(TENANT, "kob-1")

    given("a provisioned user with an activation code and a PIN") {
        val f = Fixture(ssmsUser(activationCode = "ABC123"))

        `when`("the device activates with the code and the PIN") {
            val deviceId = f.ssms.activate(ref, "ABC123", PIN)

            then("a device is bound to the user") {
                deviceId shouldNotBe ""
                f.ssms.deviceOf(ref) shouldBe deviceId
            }
        }

        `when`("the same code is used for a second activation") {
            val result = runCatching { f.ssms.activate(ref, "ABC123", PIN) }

            then("it is refused - one activation code, one activation, as with any real activation secret") {
                shouldThrow<KobilRejectedException> { result.getOrThrow() }
            }
        }
    }

    given("another provisioned user with an activation code") {
        val user = ssmsUser(activationCode = "ABC123")
        val f = Fixture(user)

        `when`("the device activates with a wrong code") {
            val result = runCatching { f.ssms.activate(ref, "WRONG", PIN) }

            then("it is refused and binds nothing") {
                shouldThrow<KobilRejectedException> { result.getOrThrow() }
                user.deviceId.shouldBeNull()
            }
        }

        `when`("the device activates under another tenant") {
            val result = runCatching { f.ssms.activate(KobilUserRef("other", "kob-1"), "ABC123", PIN) }

            then("the user is simply unknown") {
                shouldThrow<KobilRejectedException> { result.getOrThrow() }
            }
        }
    }

    given("an activated device that reports itself rooted") {
        val f = Fixture(ssmsUser(deviceId = "dev-1", riskSignals = "ROOTED"))

        `when`("the user logs in with the PIN") {
            val otp = f.ssms.login(ref, PIN)

            then("an assertion is filed, and only the OTP that points at it is handed back") {
                f.store.keys shouldBe setOf(otp)
            }
        }

        `when`("the relying party redeems that OTP") {
            val otp = f.store.keys.single()
            val verification = f.ssms.verifyOtp(ref, otp)

            then("it learns the device and its risk signals") {
                verification shouldBe KobilOtpVerification("dev-1", setOf(KobilRisk.ROOTED))
            }
        }

        `when`("the same OTP is redeemed again") {
            val replay = f.ssms.verifyOtp(ref, f.store.keys.single())

            then("it is spent - a replayed reference buys nothing") {
                replay.shouldBeNull()
            }
        }
    }

    given("an activated device") {
        val f = Fixture(ssmsUser(deviceId = "dev-1"))

        `when`("the user logs in with a wrong PIN") {
            val result = runCatching { f.ssms.login(ref, "87654321") }

            then("it is refused and produces no assertion at all") {
                shouldThrow<KobilRejectedException> { result.getOrThrow() }
                f.store.shouldBeEmpty()
            }
        }
    }

    given("two activated users of one tenant, and an OTP of the first") {
        val f = Fixture(ssmsUser(deviceId = "dev-1"), ssmsUser(userId = "kob-2", deviceId = "dev-2"))
        val otp = f.ssms.login(ref, PIN)

        `when`("the OTP is redeemed for the second user") {
            val verification = f.ssms.verifyOtp(KobilUserRef(TENANT, "kob-2"), otp)

            then("it is as good as unknown - unknown, spent and foreign are deliberately one answer") {
                verification.shouldBeNull()
            }
        }
    }

    given("a user with no device yet") {
        val f = Fixture(ssmsUser())

        `when`("the user tries to log in") {
            val result = runCatching { f.ssms.login(ref, PIN) }

            then("a login cannot happen") {
                shouldThrow<KobilRejectedException> { result.getOrThrow() }
            }
        }
    }
})
