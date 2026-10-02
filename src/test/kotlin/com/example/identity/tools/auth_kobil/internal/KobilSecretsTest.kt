package com.example.identity.tools.auth_kobil.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldHaveLength
import io.kotest.matchers.string.shouldMatch

class KobilSecretsTest : BehaviorSpec({

    given("secrets configured for an 8-digit PIN") {
        val secrets = KobilSecrets(pinLength = 8)

        `when`("a PIN is minted") {
            val pin = secrets.newPin()

            then("it has the configured length and is all digits - the SDK takes a numeric PIN") {
                pin shouldMatch Regex("\\d{8}")
            }
        }

        `when`("two PINs are minted") {
            val first = secrets.newPin()
            val second = secrets.newPin()

            then("they differ") {
                first shouldNotBe second
            }
        }
    }

    given("a freshly minted unlock secret and its hash") {
        val secrets = KobilSecrets(pinLength = 8)
        val secret = secrets.newUnlockSecret()
        val hash = secrets.hash(secret)

        then("it is long enough that a plain digest is the right store for it") {
            // 32 random bytes, base64url without padding: no small preimage space to defend, which
            // is what a password KDF would be for.
            secret shouldHaveLength 43
        }

        then("its hash matches only itself") {
            secrets.matches(secret, hash) shouldBe true
            secrets.matches(secrets.newUnlockSecret(), hash) shouldBe false
        }

        then("a garbage candidate is rejected rather than blowing up") {
            secrets.matches("not-a-secret", hash) shouldBe false
        }
    }
})
