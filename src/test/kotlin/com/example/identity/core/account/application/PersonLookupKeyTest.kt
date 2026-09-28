package com.example.identity.core.account.application

import com.example.identity.core.account.application.PersonLookupKey
import com.example.identity.core.account.application.PreviousLookupSecrets
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import java.time.LocalDate

/** Rotating the change log's search secret. */
class PersonLookupKeyTest : BehaviorSpec({
    val born = LocalDate.parse("1985-06-15")
    val oldSecret = "a".repeat(32)
    val newSecret = "b".repeat(32)

    given("a secret rotated from id 1 to id 2, the old one kept for searching") {
        val before = PersonLookupKey(oldSecret, "1", PreviousLookupSecrets())
        val after = PersonLookupKey(newSecret, "2", PreviousLookupSecrets(mapOf("1" to oldSecret)))
        val written = before.of("Müller", "Max", born)!!

        then("new keys are written with the new secret and carry its id") {
            val key = after.of("Müller", "Max", born)!!
            key.keyId shouldBe "2"
            key.value shouldNotBe written.value
        }

        then("a search still finds what the old secret wrote") {
            after.candidates("Müller", "Max", born) shouldContain written.value
            after.knownKeyIds shouldBe setOf("1", "2")
        }
    }

    given("the secret application.yml ships for the demo") {
        val shipped = java.io.File("src/main/resources/application.yml").readText()

        then("is the one PersonLookupKey recognizes as the demo secret") {
            shipped shouldContain "CHANGE_LOG_LOOKUP_SECRET:${PersonLookupKey.DEMO_SECRET}}"
            PersonLookupKey(PersonLookupKey.DEMO_SECRET, "1", PreviousLookupSecrets()).usesDemoSecret shouldBe true
            PersonLookupKey(newSecret, "1", PreviousLookupSecrets()).usesDemoSecret shouldBe false
        }
    }
})
