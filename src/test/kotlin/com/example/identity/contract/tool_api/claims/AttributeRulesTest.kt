package com.example.identity.contract.tool_api.claims

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * Unit test for the anchor-role vocabulary (docs/02-domaenenmodell.md Abschnitt 6): one rule per
 * attribute type, applied identically on write and lookup.
 * PERSON_ID, EID_RESTRICTED_ID (ADR-19) and EMAIL are anchors; PHONE_NUMBER belongs to its method module.
 */
class AttributeRulesTest : BehaviorSpec({

    given("the authority of each attribute type") {
        then("PERSON_ID is locally owned, established and replaced at loa2, immutable, not the holder's to withdraw") {
            AttributeType.PERSON_ID.authority shouldBe AttributeAuthority.Local(
                AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = false, retractableByHolder = false)
            )
        }
        then("EMAIL is locally owned, established at loa1 but replaced only at loa2, replaceable, withdrawable by the holder") {
            AttributeType.EMAIL.authority shouldBe AttributeAuthority.Local(
                AnchorRule(AnchorAcrFloor(AcrLevel.LOA1, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = true)
            )
        }
        then("EID_RESTRICTED_ID is locally owned, established and replaced at loa2, replaceable, not the holder's to withdraw (ADR-19)") {
            AttributeType.EID_RESTRICTED_ID.authority shouldBe AttributeAuthority.Local(
                AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = false)
            )
        }
        then("the holder may withdraw exactly EMAIL - no identity anchor") {
            AttributeType.entries.filter { it.anchorRule?.retractableByHolder == true } shouldBe listOf(AttributeType.EMAIL)
        }
        then("the locally anchored attributes are exactly PERSON_ID, VERSNR, the two card pseudonyms and EMAIL") {
            AttributeType.entries.filter { it.isLocalAnchor } shouldBe
                listOf(
                    AttributeType.PERSON_ID, AttributeType.MEMBER_NUMBER, AttributeType.EID_RESTRICTED_ID,
                    AttributeType.NECT_RESTRICTED_ID, AttributeType.EMAIL
                )
        }
        then("the pseudonym Nect reads is anchored like our own, but as a separate anchor") {
            AttributeType.NECT_RESTRICTED_ID.authority shouldBe AttributeType.EID_RESTRICTED_ID.authority
        }
        then("master data owns the identifying attributes it is the register for") {
            AttributeType.entries.filter { it.authority == AttributeAuthority.PersonDirectory } shouldBe
                listOf(
                    AttributeType.KVNR, AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE,
                    AttributeType.STREET_ADDRESS, AttributeType.POSTAL_CODE, AttributeType.LOCALITY
                )
        }
        then("a method module owns exactly what it enrolled itself: the phone number and the fact of a password") {
            AttributeType.entries.filter { it.authority == AttributeAuthority.MethodModule } shouldBe
                listOf(AttributeType.PHONE_NUMBER, AttributeType.PASSWORD_EXISTS)
        }
        // Only AttributeAuthority.Local has an AnchorRule, so the unpaired state cannot be constructed.
    }

    given("an email anchor value") {
        `when`("a value with spaces and capitals is normalized") {
            val folded = AttributeType.EMAIL.normalizeAnchorValue("  Max@Example.COM ")

            then("it folds to trimmed lowercase") {
                folded shouldBe "max@example.com"
            }
        }
        `when`("a malformed value is normalized") {
            val malformed = runCatching { AttributeType.EMAIL.normalizeAnchorValue("not-an-email") }

            then("it fails explicitly (Email.parse)") {
                shouldThrow<IllegalArgumentException> { malformed.getOrThrow() }
            }
        }
    }

    given("a kvnr, which is no local anchor") {
        `when`("a well-formed value is normalized for a local lookup") {
            val wellFormed = runCatching { AttributeType.KVNR.normalizeAnchorValue(" a123456789 ") }

            then("it refuses because KVNR belongs to the live master data") {
                shouldThrow<IllegalStateException> { wellFormed.getOrThrow() }
            }
        }
        `when`("a malformed value is normalized") {
            val malformed = runCatching { AttributeType.KVNR.normalizeAnchorValue("not-a-kvnr") }

            then("it fails explicitly (Kvnr.parse)") {
                shouldThrow<IllegalArgumentException> { malformed.getOrThrow() }
            }
        }
    }

    given("a personId anchor value") {
        `when`("a lowercase value with spaces is normalized") {
            val folded = AttributeType.PERSON_ID.normalizeAnchorValue(" p000000007 ")

            then("it folds to the canonical Partnernummer - trimmed, uppercase") {
                folded shouldBe "P000000007"
            }
        }
        `when`("an invalid value is normalized") {
            val invalid = runCatching { AttributeType.PERSON_ID.normalizeAnchorValue("7") }

            then("it fails explicitly instead of falling back to weaker matching") {
                shouldThrow<IllegalArgumentException> { invalid.getOrThrow() }
            }
        }
    }

    given("a restricted id anchor value") {
        `when`("it is normalized") {
            val trimmed = AttributeType.EID_RESTRICTED_ID.normalizeAnchorValue("  T0103005K1D5S0V8T9W6UM2RTX ")

            then("it only trims - the card's byte string is the canonical form") {
                trimmed shouldBe "T0103005K1D5S0V8T9W6UM2RTX"
            }
        }
    }

    given("a non-anchor attribute value") {
        `when`("it is normalized") {
            val result = runCatching { AttributeType.FAMILY_NAME.normalizeAnchorValue("Muster") }

            then("it fails explicitly instead of silently returning the raw value") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }
})
