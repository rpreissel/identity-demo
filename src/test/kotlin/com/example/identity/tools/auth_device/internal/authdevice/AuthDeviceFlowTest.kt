package com.example.identity.tools.auth_device.internal.authdevice

import com.example.identity.contract.tool_api.device.UserVerification
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AuthDeviceFlowTest : BehaviorSpec({

    given("an enrolled thumbprint") {
        `when`("a key with the same thumbprint is submitted") {
            val decision = AuthDeviceFlow.decide("thumb-a", "thumb-a", UserVerification.PIN)

            then("it completes with the submitted user verification") {
                decision shouldBe AuthDeviceDecision.Complete(UserVerification.PIN)
            }
        }

        `when`("a key with another thumbprint is submitted") {
            val decision = AuthDeviceFlow.decide("thumb-a", "thumb-b", UserVerification.BIOMETRIC)

            then("it is rejected as the wrong device") {
                decision shouldBe AuthDeviceDecision.WrongDevice
            }
        }
    }

    given("no enrolled thumbprint at all") {
        `when`("a key is submitted") {
            val decision = AuthDeviceFlow.decide("thumb-a", null, UserVerification.PIN)

            then("it is rejected as the wrong device") {
                decision shouldBe AuthDeviceDecision.WrongDevice
            }
        }
    }
})
