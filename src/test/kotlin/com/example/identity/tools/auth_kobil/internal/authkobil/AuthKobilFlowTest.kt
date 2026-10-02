package com.example.identity.tools.auth_kobil.internal.authkobil

import com.example.identity.simulation.kobil.KobilOtpVerification
import com.example.identity.simulation.kobil.KobilRisk
import com.example.identity.contract.tool_api.device.UserVerification
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The precedence of the redemption decision, in isolation - no Spring, no repositories, no provider.
 * Each single outcome is covered through the handler by [AuthKobilToolHandlerTest].
 */
class AuthKobilFlowTest : BehaviorSpec({

    val enrolled = "dev-enrolled"
    val blocking = setOf(KobilRisk.ROOTED, KobilRisk.APP_TAMPERED)

    given("no live PIN release, and a clean assertion from the enrolled device") {
        val verification = KobilOtpVerification(enrolled, emptySet())

        `when`("the flow decides") {
            val decision = AuthKobilFlow.decide(verification, enrolled, release = null, blockingRisks = blocking)

            then("it is not released, whatever the OTP says - an unlock was never presented") {
                decision shouldBe AuthKobilDecision.NotReleased
            }
        }
    }

    given("a live release, and an assertion from another device that also reports a blocking risk") {
        val verification = KobilOtpVerification("dev-other", setOf(KobilRisk.ROOTED))

        `when`("the flow decides") {
            val decision = AuthKobilFlow.decide(verification, enrolled, UserVerification.BIOMETRIC, blocking)

            then("the device is refused before any risk is even considered") {
                decision shouldBe AuthKobilDecision.WrongDevice
            }
        }
    }

    given("a live release, and the enrolled device reporting a blocking and a harmless signal") {
        val verification = KobilOtpVerification(enrolled, setOf(KobilRisk.ROOTED, KobilRisk.OS_OUTDATED))

        `when`("the flow decides") {
            val decision = AuthKobilFlow.decide(verification, enrolled, UserVerification.BIOMETRIC, blocking)

            then("the blocking one outweighs the harmless one, and only it is named") {
                decision shouldBe AuthKobilDecision.RiskRejected(setOf(KobilRisk.ROOTED))
            }
        }
    }
})
