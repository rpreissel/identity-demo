package com.example.identity.contract.tool_api.claims

import com.example.identity.contract.tool_api.ToolId
import com.example.identity.tools.auth_password.PASSWORD_EXISTS
import com.example.identity.tools.auth_sms.PHONE_NUMBER
import com.example.identity.tools.ident_nect.NECT_RESTRICTED_ID
import com.example.identity.tools.ident_eid.EID_RESTRICTED_ID
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.FactorType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Pins the claims vocabulary of tool_api: [AttributeType], [ClaimSource] with its [ClaimTrust]s,
 * the value check of a [Claim] and the [ClaimDeclaration] check [assertClaimsCovered]. The
 * orchestrator's `requiresSatisfied` is tested there.
 */
class ClaimsTest : BehaviorSpec({
    given("the attribute types") {
        // The modules' own attributes exist once their module is loaded; load all of them first.
        StrategyTestFixtures.catalog
        then("wire names are stable - they become account.claim.attribute_type values") {
            AttributeType.declared.associateWith { it.wireName } shouldBe mapOf(
                AttributeType.PERSON_ID to "person_id",
                AttributeType.KVNR to "kvnr",
                AttributeType.MEMBER_NUMBER to "member_number",
                EID_RESTRICTED_ID to "restricted_id",
                NECT_RESTRICTED_ID to "nect_restricted_id",
                AttributeType.FAMILY_NAME to "family_name",
                AttributeType.GIVEN_NAMES to "given_names",
                AttributeType.BIRTH_DATE to "birth_date",
                AttributeType.STREET_ADDRESS to "street_address",
                AttributeType.POSTAL_CODE to "postal_code",
                AttributeType.LOCALITY to "locality",
                AttributeType.EMAIL to "email",
                PHONE_NUMBER to "phone_number",
                PASSWORD_EXISTS to "password_exists",
            )
        }
    }

    given("the claim sources") {
        then("the two named constants carry their wire values") {
            ClaimSource.PERSON_DIRECTORY.value shouldBe "person_directory"
            ClaimSource.SELF_REPORTED.value shouldBe "self-reported"
        }
        then("a proving tool is named by its toolId") {
            ClaimSource("ident-eid").value shouldBe "ident-eid"
        }
    }

    given("the claim trust levels") {
        then("rank encodes the precedence order: Stammdaten > Proven > Self-reported") {
            (ClaimTrust.AUTHORITATIVE.rank > ClaimTrust.PROVEN.rank) shouldBe true
            (ClaimTrust.PROVEN.rank > ClaimTrust.SELF_REPORTED.rank) shouldBe true
        }
        then("every source kind maps to its level") {
            ClaimSource.PERSON_DIRECTORY.claimTrust shouldBe ClaimTrust.AUTHORITATIVE
            ClaimSource.SELF_REPORTED.claimTrust shouldBe ClaimTrust.SELF_REPORTED
            ClaimSource("ident-eid").claimTrust shouldBe ClaimTrust.PROVEN
        }
    }

    given("malformed shared identity values") {
        `when`("a person id that is no Partnernummer is validated") {
            val result = runCatching { Claim(AttributeType.PERSON_ID, "not-a-number", ClaimSource.PERSON_DIRECTORY).validateValue() }

            then("it is rejected") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
        `when`("a birth date that is no ISO date is validated") {
            val result = runCatching { Claim(AttributeType.BIRTH_DATE, "31.12.1970", ClaimSource.PERSON_DIRECTORY).validateValue() }

            then("it is rejected") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
        `when`("a blank email is validated") {
            val result = runCatching { Claim(AttributeType.EMAIL, " ", ClaimSource.SELF_REPORTED).validateValue() }

            then("it is rejected") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("ident-fsc, declaring KVNR and the names from the master data") {
        val descriptor = StrategyTestFixtures.tool("ident-fsc")

        `when`("a run reports claims that match the declaration") {
            val result = runCatching {
                assertClaimsCovered(
                    descriptor,
                    listOf(
                        Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY, AcrLevel.LOA2),
                        Claim(AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY)
                    )
                )
            }

            then("they are accepted") {
                result.isSuccess shouldBe true
            }
        }
        `when`("a run reports no claim") {
            val result = runCatching { assertClaimsCovered(descriptor, emptyList()) }

            then("the empty report is accepted") {
                result.isSuccess shouldBe true
            }
        }
        `when`("a run reports an undeclared attribute type") {
            val result = runCatching {
                assertClaimsCovered(descriptor, listOf(Claim(AttributeType.EMAIL, "a@b.de", ClaimSource(descriptor.toolId.value))))
            }

            then("it is rejected") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message shouldContain "declares none"
            }
        }
        `when`("a run reports a claim source that differs from the declaration") {
            val result = runCatching {
                assertClaimsCovered(descriptor, listOf(Claim(AttributeType.KVNR, "A123456789", ClaimSource(descriptor.toolId.value))))
            }

            then("it is rejected") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message shouldContain "but declares"
            }
        }
        `when`("a run reports more than one claim for the same attribute type") {
            val result = runCatching {
                assertClaimsCovered(
                    descriptor,
                    listOf(
                        Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY),
                        Claim(AttributeType.KVNR, "A987654321", ClaimSource.PERSON_DIRECTORY)
                    )
                )
            }

            then("it is rejected") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }.message shouldContain "more than one claim"
            }
        }
    }
})
