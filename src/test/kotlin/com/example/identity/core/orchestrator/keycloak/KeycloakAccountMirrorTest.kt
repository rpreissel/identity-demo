package com.example.identity.core.orchestrator.keycloak

import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.directory.PersonRecord
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.AccountProfile
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * Pure unit test of the account -> Keycloak-user mapping the user federation reads (ADR-38,
 * `KeycloakAccountViews`) - the precedence is the point:
 * register person first (authoritative master data), then the account's own attested claims
 * (full-attested prospect, ADR-18), placeholders only for an account that has neither.
 */
class KeycloakAccountMirrorTest : BehaviorSpec({

    fun profile(personId: PartnerNumber?) = AccountProfile(
        accountId = AccountId(1L), personId = personId, authenticationMethods = emptyList(),
        email = "who@example.test", emailConfirmedAt = null
    )

    given("an account bound to a register person, with contradicting attested claims") {
        // The port hands out one street line; joining "Musterweg" and "1" is the directory's job.
        val person = PersonRecord(
            personId = PartnerNumber("P000000007"),
            kvnr = "A123456789", familyName = "Mustermann", givenNames = "Max", birthDate = LocalDate.of(1990, 1, 1),
            streetAddress = "Musterweg 1", postalCode = "12345", locality = "Musterstadt", memberNumber = "10000001"
        )
        val attested = mapOf(
            AttributeType.FAMILY_NAME to "Anderer", AttributeType.GIVEN_NAMES to "Falscher", AttributeType.BIRTH_DATE to "2000-01-01",
            AttributeType.STREET_ADDRESS to "Andere Gasse 9", AttributeType.POSTAL_CODE to "99999", AttributeType.LOCALITY to "Nirgendwo"
        )

        `when`("mirroring the account") {
            val mirror = keycloakUserMirror(profile(personId = PartnerNumber("P000000007")), person, attested)

            then("names, attributes and address come from the register, claims never override it") {
                mirror.firstName shouldBe "Max"
                mirror.lastName shouldBe "Mustermann"
                mirror.attributes shouldBe mapOf(
                    "personId" to "P000000007", "kvnr" to "A123456789", "versnr" to "10000001", "birthDate" to "1990-01-01",
                    "streetAddress" to "Musterweg 1", "postalCode" to "12345", "locality" to "Musterstadt"
                )
            }
        }
    }

    given("a full-attested prospect: no register person, but identity claims on the account") {
        val attested = mapOf(
            AttributeType.FAMILY_NAME to "Musterfrau",
            AttributeType.GIVEN_NAMES to "Erika",
            AttributeType.BIRTH_DATE to "1985-05-05",
            AttributeType.STREET_ADDRESS to "Musterweg 1",
            AttributeType.POSTAL_CODE to "12345",
            AttributeType.LOCALITY to "Musterstadt"
        )

        `when`("mirroring the account") {
            val mirror = keycloakUserMirror(profile(personId = null), null, attested)

            then("names, birth date and address fall back to the attested claims; personId and kvnr stay absent") {
                mirror.firstName shouldBe "Erika"
                mirror.lastName shouldBe "Musterfrau"
                mirror.attributes shouldBe mapOf(
                    "birthDate" to "1985-05-05",
                    "streetAddress" to "Musterweg 1", "postalCode" to "12345", "locality" to "Musterstadt"
                )
            }
        }
    }

    given("an account with neither a register person nor attested claims (enrollment first)") {
        `when`("mirroring the account") {
            val mirror = keycloakUserMirror(profile(personId = null), null, emptyMap())

            then("the placeholders stand in, and no attribute is written") {
                mirror.firstName shouldBe UNIDENTIFIED_FIRST_NAME
                mirror.lastName shouldBe UNIDENTIFIED_LAST_NAME
                mirror.attributes shouldBe emptyMap()
            }
        }
    }

    given("a register person with partial master data (no kvnr, no address on record), and older attested claims") {
        val person = PersonRecord(
            personId = PartnerNumber("P000000009"), kvnr = null, familyName = "Knapp", givenNames = "Karl",
            birthDate = null, streetAddress = null, postalCode = null, locality = null, memberNumber = null
        )
        val attested = mapOf(
            AttributeType.BIRTH_DATE to "1970-01-01",
            AttributeType.STREET_ADDRESS to "Lückenweg 2", AttributeType.POSTAL_CODE to "54321", AttributeType.LOCALITY to "Lückendorf"
        )

        `when`("mirroring the account") {
            val mirror = keycloakUserMirror(profile(personId = PartnerNumber("P000000009")), person, attested)

            then("a gap in the register stays a gap - an old attested claim never resurfaces for a bound account (ADR-34)") {
                mirror.attributes shouldBe mapOf("personId" to "P000000009")
            }
        }
    }
})
