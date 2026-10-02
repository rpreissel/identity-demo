package com.example.identity.tools.auth_device.internal.enrolldevice

import com.example.identity.contract.tool_api.device.DevicePublicKey
import com.example.identity.contract.tool_api.device.UserVerification
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class EnrollDeviceFlowTest : BehaviorSpec({

    given("an already-verified device proof") {
        val key = DevicePublicKey(kty = "EC", crv = "P-256", x = "x", y = "y", thumbprint = "thumb-a")

        `when`("it arrives over a channel with another DPoP key") {
            val decision = EnrollDeviceFlow.decide(EnrollDeviceInput(key, UserVerification.PIN, "channel-key", "My Phone"))

            then("it enrolls, carrying the input through unchanged") {
                decision shouldBe EnrollDeviceDecision.Enroll(key, UserVerification.PIN, "channel-key", "My Phone")
            }
        }

        `when`("it arrives over a channel whose DPoP key is the device key") {
            val decision = EnrollDeviceFlow.decide(EnrollDeviceInput(key, UserVerification.PIN, key.thumbprint, "My Phone"))

            then("it refuses the device key") {
                decision shouldBe EnrollDeviceDecision.SameKeyAsChannel
            }
        }
    }
})
