package com.example.identity.core.account.application

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldMatch
import java.io.File
import java.time.LocalDate

/** The change log's search key (ADR-39): what makes two inputs the same person, and rotating its secret. */
class PersonLookupKeyTest : BehaviorSpec({
    val born = LocalDate.parse("1985-06-15")
    val oldSecret = "a".repeat(32)
    val newSecret = "b".repeat(32)

    given("a lookup key with secret id 1") {
        val lookupKey = PersonLookupKey(oldSecret, "1", PreviousLookupSecrets())

        `when`("computing the key for Müller, Max, born 1985-06-15") {
            val key = lookupKey.of("Müller", "Max", born)!!

            then("it is a 64-digit hex keyed hash that names secret id 1 - no name or date is readable in it") {
                key.value shouldMatch Regex("[0-9a-f]{64}")
                key.keyId shouldBe "1"
            }
        }

        `when`("computing the key for the same person in another spelling a passport would agree with") {
            val key = lookupKey.of(" mueller ", "MAX", born)

            then("it is the same key") {
                key shouldBe lookupKey.of("Müller", "Max", born)
            }
        }

        `when`("computing the key for the same name born a day later") {
            val key = lookupKey.of("Müller", "Max", born.plusDays(1))

            then("it is another key") {
                key shouldNotBe lookupKey.of("Müller", "Max", born)
            }
        }

        `when`("computing the key without a date of birth") {
            val key = lookupKey.of("Müller", "Max", null)

            then("there is none - a partial key would match far too many people") {
                key.shouldBeNull()
            }
        }
    }

    given("a secret rotated from id 1 to id 2, the old one kept for searching") {
        val before = PersonLookupKey(oldSecret, "1", PreviousLookupSecrets())
        val after = PersonLookupKey(newSecret, "2", PreviousLookupSecrets(mapOf("1" to oldSecret)))
        val written = before.of("Müller", "Max", born)!!

        `when`("computing a new key") {
            val key = after.of("Müller", "Max", born)!!

            then("it is written with the new secret and carries its id") {
                key.keyId shouldBe "2"
                key.value shouldNotBe written.value
            }
        }

        `when`("searching for the same person") {
            val candidates = after.candidates("Müller", "Max", born)

            then("the search still finds what the old secret wrote, and knows both ids") {
                candidates shouldContain written.value
                after.knownKeyIds shouldBe setOf("1", "2")
            }
        }
    }

    given("the secret application.yml ships for the demo") {
        val shipped = File("src/main/resources/application.yml").readText()

        then("application.yml ships PersonLookupKey's demo secret") {
            shipped shouldContain "CHANGE_LOG_LOOKUP_SECRET:${PersonLookupKey.DEMO_SECRET}}"
        }

        then("PersonLookupKey recognizes that secret, and no other, as the demo secret") {
            PersonLookupKey(PersonLookupKey.DEMO_SECRET, "1", PreviousLookupSecrets()).usesDemoSecret shouldBe true
            PersonLookupKey(newSecret, "1", PreviousLookupSecrets()).usesDemoSecret shouldBe false
        }
    }
})
