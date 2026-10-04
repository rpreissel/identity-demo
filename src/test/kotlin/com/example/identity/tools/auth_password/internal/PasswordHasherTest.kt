package com.example.identity.tools.auth_password.internal

import com.example.identity.TEST_NOW
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

/** Argon2id for every hash; weaker Argon2id parameters move over on the next successful login. */
class PasswordHasherTest : BehaviorSpec({

    given("a new password") {
        `when`("it is hashed") {
            val hash = PasswordHasher.hash("correct-horse-battery")

            then("the hash is Argon2id with today's parameters") {
                hash shouldStartWith "\$argon2id\$"
                PasswordHasher.needsRehash(hash) shouldBe false
            }

            then("today's parameters are the OWASP values: 19 MiB memory, 2 iterations, 1 lane") {
                hash.split("\$")[3] shouldBe "m=19456,t=2,p=1"
            }

            then("it verifies this password and no other") {
                PasswordHasher.matches("correct-horse-battery", hash) shouldBe true
                PasswordHasher.matches("wrong-horse-battery", hash) shouldBe false
            }
        }
    }

    given("an Argon2id hash with weaker parameters than today's") {
        val weak = Argon2PasswordEncoder(16, 32, 1, 4_096, 1).encode("correct-horse-battery")

        then("it still verifies") {
            PasswordHasher.matches("correct-horse-battery", weak) shouldBe true
        }

        then("it is due for a rehash") {
            PasswordHasher.needsRehash(weak) shouldBe true
        }
    }

    given("an enrollment with a weaker Argon2id hash") {
        val enrollment = AuthPasswordEnrollment(
            passwordHash = Argon2PasswordEncoder(16, 32, 1, 4_096, 1).encode("correct-horse-battery"),
            createdAt = TEST_NOW
        )

        `when`("it is upgraded after a successful login") {
            PasswordHasher.upgrade(enrollment, "correct-horse-battery")

            then("the hash has today's parameters and still verifies") {
                PasswordHasher.needsRehash(enrollment.passwordHash) shouldBe false
                PasswordHasher.matches("correct-horse-battery", enrollment.passwordHash) shouldBe true
            }
        }
    }

    given("a hash in any other format") {
        then("it never matches - there is no older format to accept") {
            PasswordHasher.matches("x", "210000:c2FsdA==:aGFzaA==") shouldBe false
        }
    }

    given("no stored hash at all") {
        then("nothing matches (and the full work is still spent - see PasswordHasher.matches)") {
            PasswordHasher.matches("anything", null) shouldBe false
        }
    }
})
