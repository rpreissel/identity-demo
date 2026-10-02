package com.example.identity.tools.auth_kobil.internal.enrollkobil

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * The incomplete confirmations the handler test does not reach. What a complete confirmation
 * enrolls, and that an unknown device changes nothing, is covered by [EnrollKobilToolHandlerTest].
 */
class EnrollKobilFlowTest : BehaviorSpec({

    given("a device known to the provider, and a consent") {
        `when`("the activation is not confirmed at all") {
            val decision = EnrollKobilFlow.decide(null, biometricConsent = true, deviceId = "dev-1")

            then("nothing changes") {
                decision shouldBe EnrollKobilDecision.Unchanged
            }
        }
    }

    given("a device known to the provider, and a confirmed activation") {
        `when`("the consent is missing") {
            val decision = EnrollKobilFlow.decide(true, biometricConsent = null, deviceId = "dev-1")

            then("nothing changes - there is no default for a consent") {
                decision shouldBe EnrollKobilDecision.Unchanged
            }
        }
    }
})
