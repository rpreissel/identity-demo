package com.example.identity.tools.auth_device.internal.authdevice

import com.example.identity.contract.tool_api.device.UserVerification
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AuthDeviceFlowTest : BehaviorSpec({

    given("a submitted key matching the enrolled thumbprint") {
        then("it completes with the submitted user verification") {
            AuthDeviceFlow.decide("thumb-a", "thumb-a", UserVerification.PIN) shouldBe
                AuthDeviceDecision.Complete(UserVerification.PIN)
        }
    }

    given("a submitted key not matching the enrolled thumbprint") {
        then("it is rejected as the wrong device") {
            AuthDeviceFlow.decide("thumb-a", "thumb-b", UserVerification.BIOMETRIC) shouldBe AuthDeviceDecision.WrongDevice
        }
    }

    given("no enrolled thumbprint at all") {
        then("it is rejected as the wrong device") {
            AuthDeviceFlow.decide("thumb-a", null, UserVerification.PIN) shouldBe AuthDeviceDecision.WrongDevice
        }
    }

    given("describe()") {
        then("it names step auth with no stepData") {
            AuthDeviceState.describe() shouldBe ("auth" to null)
        }
    }
})
