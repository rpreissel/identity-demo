package com.example.identity.tools.auth_kobil

import com.example.identity.contract.tool_api.ToolRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Pins what the two KOBIL tools declare about themselves. Pure unit test: descriptors are plain
 * Kotlin objects, no Spring context needed.
 */
class KobilDescriptorsTest : BehaviorSpec({

    given("the KOBIL descriptors") {
        then("both price the method identically - a sibling-by-method-name lookup must not depend on bean order") {
            EnrollKobilDescriptor.method shouldBe AuthKobilDescriptor.method
            EnrollKobilDescriptor.factorTypes shouldBe AuthKobilDescriptor.factorTypes
            EnrollKobilDescriptor.maxAcr shouldBe AuthKobilDescriptor.maxAcr
        }

        then("their roles differ - that pair is what tells one tool of a method from the other") {
            EnrollKobilDescriptor.role shouldBe ToolRole.ENROLLMENT
            AuthKobilDescriptor.role shouldBe ToolRole.KNOWN_ACCOUNT_AUTH
        }
    }

    given("an instance enrolled on the installation with DPoP key key-a") {
        val details = mapOf(KOBIL_BINDING_KEY_REF to "key-a")
        val binding = AuthKobilDescriptor.keyBinding

        then("it matches only that installation") {
            binding.livesOn(details, "key-a") shouldBe true
            binding.livesOn(details, "key-b") shouldBe false
        }

        then("a caller without a key never matches it") {
            binding.livesOn(details, null) shouldBe false
        }
    }

    given("an instance that records only its KOBIL device identifier") {
        val details = mapOf(KOBIL_DEVICE_ID to "dev-1")

        then("the rule reads the binding key, never the device identifier - those answer different questions") {
            // The KOBIL identifier is only known after an assertion has been redeemed, so it
            // cannot decide whether to OFFER this credential.
            AuthKobilDescriptor.keyBinding.livesOn(details, "dev-1") shouldBe false
        }
    }
})
