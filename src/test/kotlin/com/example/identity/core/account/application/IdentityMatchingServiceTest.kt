package com.example.identity.core.account.application

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.directory.ClaimedIdentity
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.contract.tool_api.directory.MatchedVia
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.directory.Resolution
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.infrastructure.AccountAnchor
import com.example.identity.core.account.infrastructure.AccountAnchorRepository
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.AccountClaimRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate

/**
 * Pins the resolution policy of the central identity matching (docs/02-domaenenmodell.md #6):
 * anchor-only resolution (ADR-19), where PERSON_ID ranks highest, a KVNR resolves live through the
 * register, and the eID card pseudonym is the recognition anchor for eid. Also pins the
 * account-scoped checks `attestedIdentityMatches` (ADR-18) and `attestationFits`.
 */
class IdentityMatchingServiceTest : BehaviorSpec({

    given("a person_id claim and a kvnr claim from the register, with a person_id anchor") {
        val fixture = IdentityMatchingFixture()
        val claims = setOf(
            Claim(AttributeType.PERSON_ID, "P000000042", ClaimSource.PERSON_DIRECTORY),
            Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY),
            Claim(AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY)
        )
        fixture.anchor(AttributeType.PERSON_ID, "P000000042", AccountId(42L))

        `when`("resolving the claims") {
            val resolution = fixture.service.resolve(claims)

            then("a person_id claim skips the KVNR lookup and resolves through its own anchor") {
                resolution shouldBe Resolution.ExistingAccount(AccountId(42L), MatchedVia.Anchor(AttributeType.PERSON_ID))
                verify(exactly = 0) { fixture.personDirectory.findPersonIdByKvnr(any()) }
                verify(exactly = 0) { fixture.personDirectory.matchesMasterData(any(), any()) }
            }
        }
    }

    given("a kvnr claim without a person claim, and a live kvnr-to-person mapping") {
        val fixture = IdentityMatchingFixture()
        val source = ClaimSource("ident-eid")
        val claims = setOf(
            Claim(AttributeType.KVNR, "A123456789", source),
            Claim(AttributeType.FAMILY_NAME, "Muster", source)
        )
        every { fixture.personDirectory.findPersonIdByKvnr("A123456789") } returns PartnerNumber("P000000007")
        fixture.anchor(AttributeType.PERSON_ID, "P000000007", AccountId(42L))

        `when`("resolving the claims") {
            val resolution = fixture.service.resolve(claims)

            then("the current external mapping resolves through the person_id anchor") {
                resolution shouldBe Resolution.ExistingAccount(AccountId(42L), MatchedVia.Anchor(AttributeType.PERSON_ID))
                verify(exactly = 0) { fixture.anchorRepository.findByAttributeTypeAndValue(AttributeType.KVNR, any()) }
            }
        }
    }

    given("both a person_id and a kvnr claim, with only a stale local kvnr anchor") {
        val fixture = IdentityMatchingFixture()
        val claims = setOf(
            Claim(AttributeType.PERSON_ID, "P000000007", ClaimSource.PERSON_DIRECTORY),
            Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY)
        )
        every { fixture.anchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000007") } returns null
        fixture.anchor(AttributeType.KVNR, "A123456789", AccountId(42L))

        `when`("resolving the claims") {
            val resolution = fixture.service.resolve(claims)

            then("it does not adopt an account through the stale local kvnr anchor") {
                resolution shouldBe Resolution.Unresolved
                verify(exactly = 0) { fixture.anchorRepository.findByAttributeTypeAndValue(AttributeType.KVNR, any()) }
            }
        }
    }

    given("person_id and email anchors pointing to different accounts") {
        val fixture = IdentityMatchingFixture()
        fixture.anchor(AttributeType.PERSON_ID, "P000000007", AccountId(7L))
        fixture.anchor(AttributeType.EMAIL, "other@example.com", AccountId(8L))
        val claims = listOf(
            Claim(AttributeType.PERSON_ID, "P000000007", ClaimSource.PERSON_DIRECTORY),
            Claim(AttributeType.EMAIL, "other@example.com", ClaimSource.PERSON_DIRECTORY)
        )

        `when`("resolving the claims with person_id first") {
            val result = runCatching { fixture.service.resolve(claims.toSet()) }

            then("it reports the conflicting owners") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }

        `when`("resolving the claims with email first") {
            val result = runCatching { fixture.service.resolve(claims.reversed().toSet()) }

            then("claim order cannot hide a conflicting owner") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }
    }

    given("an eid attestation whose restricted_id anchor an account already holds") {
        val fixture = IdentityMatchingFixture()
        val source = ClaimSource("ident-eid")
        val claims = setOf(
            Claim(AttributeType.FAMILY_NAME, "Muster", source),
            Claim(AttributeType.GIVEN_NAMES, "Max", source),
            Claim(AttributeType.BIRTH_DATE, "1970-01-01", source),
            Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", source)
        )
        fixture.anchor(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", AccountId(7L))

        `when`("resolving the claims") {
            val resolution = fixture.service.resolve(claims)

            then("the card pseudonym recognizes the account the earlier eid run created - ADR-19") {
                resolution shouldBe Resolution.ExistingAccount(AccountId(7L), MatchedVia.Anchor(AttributeType.EID_RESTRICTED_ID))
                // No attribute matching, no stammdaten round trip - the anchor alone decides.
                verify(exactly = 0) { fixture.personDirectory.findPersonIdByKvnr(any()) }
                verify(exactly = 0) { fixture.personDirectory.matchesMasterData(any(), any()) }
            }
        }
    }

    given("an eid attestation whose restricted_id no account holds yet") {
        val fixture = IdentityMatchingFixture()
        val source = ClaimSource("ident-eid")
        val claims = setOf(
            Claim(AttributeType.FAMILY_NAME, "Niemand", source),
            Claim(AttributeType.GIVEN_NAMES, "Niemals", source),
            Claim(AttributeType.BIRTH_DATE, "1970-01-01", source),
            Claim(AttributeType.EID_RESTRICTED_ID, "T0909090Z9X8Y7W6V5U4T3S2R1", source)
        )
        every {
            fixture.anchorRepository.findByAttributeTypeAndValue(AttributeType.EID_RESTRICTED_ID, "T0909090Z9X8Y7W6V5U4T3S2R1")
        } returns null

        `when`("resolving the claims") {
            val resolution = fixture.service.resolve(claims)

            then("nothing matches - a new Interessent, the anchor gets established by the adopting side") {
                resolution shouldBe Resolution.Unresolved
            }
        }
    }

    given("name/vorname/geburtsdatum alone - attributes, no anchor (ADR-19)") {
        val fixture = IdentityMatchingFixture()
        val source = ClaimSource("ident-eid")
        val claims = setOf(
            Claim(AttributeType.FAMILY_NAME, "Muster", source),
            Claim(AttributeType.GIVEN_NAMES, "Max", source),
            Claim(AttributeType.BIRTH_DATE, "1970-01-01", source)
        )

        `when`("resolving the claims") {
            val resolution = fixture.service.resolve(claims)

            then("no account is matched by attribute combination - attributes never resolve") {
                resolution shouldBe Resolution.Unresolved
                verify(exactly = 0) { fixture.anchorRepository.findByAttributeTypeAndValue(any(), any()) }
            }
        }
    }

    given("no claims at all") {
        val fixture = IdentityMatchingFixture()

        `when`("resolving them") {
            val resolution = fixture.service.resolve(emptySet())

            then("the resolution is a new Interessent") {
                resolution shouldBe Resolution.Unresolved
            }
        }
    }

    // attestedIdentityMatches is the guard a correlation step leans on (ADR-18).
    val eid = ClaimSource("ident-eid")
    val attested = listOf(
        accountClaim(AttributeType.FAMILY_NAME, "Muster", eid),
        accountClaim(AttributeType.GIVEN_NAMES, "Max", eid),
        accountClaim(AttributeType.BIRTH_DATE, "1985-06-15", eid)
    )
    val attestedWithAddress = attested + listOf(
        accountClaim(AttributeType.STREET_ADDRESS, "Musterstraße 1", eid),
        accountClaim(AttributeType.POSTAL_CODE, "12345", eid),
        accountClaim(AttributeType.LOCALITY, "Musterstadt", eid)
    )

    given("an account with an attested identity, and a register person without a namesake who matches it") {
        val fixture = IdentityMatchingFixture()
        every { fixture.claimRepository.findEstablished(AccountId(1L)) } returns attested
        every { fixture.personDirectory.hasNamesake(PartnerNumber("P000000042")) } returns false
        every { fixture.personDirectory.matchesMasterData(PartnerNumber("P000000042"), any()) } returns true

        `when`("checking the attested identity against that person") {
            val matches = fixture.service.attestedIdentityMatches(AccountId(1L), PartnerNumber("P000000042"))

            then("it passes, carrying exactly the attested attributes into the comparison") {
                matches shouldBe true
                verify {
                    fixture.personDirectory.matchesMasterData(
                        PartnerNumber("P000000042"),
                        ClaimedIdentity(familyName = "Muster", givenNames = "Max", birthDate = LocalDate.of(1985, 6, 15))
                    )
                }
            }
        }
    }

    given("an account with an attested identity, and a register person who contradicts it") {
        val fixture = IdentityMatchingFixture()
        every { fixture.claimRepository.findEstablished(AccountId(1L)) } returns attested
        every { fixture.personDirectory.hasNamesake(PartnerNumber("P000000099")) } returns false
        every { fixture.personDirectory.matchesMasterData(PartnerNumber("P000000099"), any()) } returns false

        `when`("checking the attested identity against somebody else's number") {
            val matches = fixture.service.attestedIdentityMatches(AccountId(1L), PartnerNumber("P000000099"))

            then("it refuses") {
                matches shouldBe false
            }
        }
    }

    given("an account without an attested address, and a register person with a namesake born the same day") {
        val fixture = IdentityMatchingFixture()
        every { fixture.claimRepository.findEstablished(AccountId(1L)) } returns attested
        every { fixture.personDirectory.hasNamesake(PartnerNumber("P000000042")) } returns true

        `when`("checking the attested identity against that person (ADR-18, addendum 2026-09-26)") {
            val matches = fixture.service.attestedIdentityMatches(AccountId(1L), PartnerNumber("P000000042"))

            then("name and date of birth are not enough - it refuses without asking") {
                matches shouldBe false
                verify(exactly = 0) { fixture.personDirectory.matchesMasterData(any(), any()) }
            }
        }
    }

    given("an account with an attested address, and a register person with a namesake born the same day") {
        val fixture = IdentityMatchingFixture()
        every { fixture.claimRepository.findEstablished(AccountId(1L)) } returns attestedWithAddress
        every { fixture.personDirectory.hasNamesake(PartnerNumber("P000000042")) } returns true
        every { fixture.personDirectory.matchesMasterData(PartnerNumber("P000000042"), any()) } returns true

        `when`("checking the attested identity against that person") {
            val matches = fixture.service.attestedIdentityMatches(AccountId(1L), PartnerNumber("P000000042"))

            then("the address is compared too") {
                matches shouldBe true
                verify {
                    fixture.personDirectory.matchesMasterData(
                        PartnerNumber("P000000042"),
                        ClaimedIdentity(
                            familyName = "Muster", givenNames = "Max", birthDate = LocalDate.of(1985, 6, 15),
                            streetAddress = "Musterstraße 1", postalCode = "12345", locality = "Musterstadt"
                        )
                    )
                }
            }
        }
    }

    given("an account whose attestation lacks the date of birth") {
        val fixture = IdentityMatchingFixture()
        every { fixture.claimRepository.findEstablished(AccountId(1L)) } returns
            attested.filterNot { it.attributeType == AttributeType.BIRTH_DATE }

        `when`("checking the attested identity against a register person") {
            val matches = fixture.service.attestedIdentityMatches(AccountId(1L), PartnerNumber("P000000042"))

            then("it refuses - a missing attribute is never skipped into a name-only match") {
                matches shouldBe false
                verify(exactly = 0) { fixture.personDirectory.matchesMasterData(any(), any()) }
            }
        }
    }

    given("an account that attested nothing at all") {
        val fixture = IdentityMatchingFixture()
        every { fixture.claimRepository.findEstablished(AccountId(1L)) } returns emptyList()

        `when`("checking the attested identity against a register person") {
            val matches = fixture.service.attestedIdentityMatches(AccountId(1L), PartnerNumber("P000000042"))

            then("it refuses without even asking - an empty ClaimedIdentity would match vacuously") {
                matches shouldBe false
                verify(exactly = 0) { fixture.personDirectory.matchesMasterData(any(), any()) }
            }
        }

        `when`("checking whether a new attestation fits") {
            val fits = fixture.service.attestationFits(AccountId(1L), setOf(Claim(AttributeType.FAMILY_NAME, "Anders", eid)))

            then("any identity fits") {
                fits shouldBe true
            }
        }
    }

    // attestationFits: an Interessent may not take a second identity.
    given("an account that attested Müller, José, born 1985-06-15") {
        val fixture = IdentityMatchingFixture()
        every { fixture.claimRepository.findEstablished(AccountId(1L)) } returns listOf(
            accountClaim(AttributeType.FAMILY_NAME, "Müller", eid),
            accountClaim(AttributeType.GIVEN_NAMES, "José", eid),
            accountClaim(AttributeType.BIRTH_DATE, "1985-06-15", eid)
        )

        `when`("the new attestation spells the same person differently") {
            val fits = fixture.service.attestationFits(AccountId(1L), setOf(
                Claim(AttributeType.FAMILY_NAME, "MUELLER", eid),
                Claim(AttributeType.GIVEN_NAMES, "Jose", eid),
                Claim(AttributeType.BIRTH_DATE, "1985-06-15", eid)
            ))

            then("it fits - case, umlauts and diacritics do not count") {
                fits shouldBe true
            }
        }

        `when`("the new attestation carries another birth date") {
            val fits = fixture.service.attestationFits(AccountId(1L), setOf(Claim(AttributeType.BIRTH_DATE, "1990-01-01", eid)))

            then("it does not fit - that is a second identity") {
                fits shouldBe false
            }
        }

        `when`("the new attestation carries another family name") {
            val fits = fixture.service.attestationFits(AccountId(1L), setOf(Claim(AttributeType.FAMILY_NAME, "Schmidt", eid)))

            then("it does not fit - that is a second identity") {
                fits shouldBe false
            }
        }
    }
})

/** The service with its three collaborators as strict mocks; each `given` builds its own. */
private class IdentityMatchingFixture {
    val anchorRepository = mockk<AccountAnchorRepository>()
    val claimRepository = mockk<AccountClaimRepository>()
    val personDirectory = mockk<PersonDirectory>()
    val service = IdentityMatchingService(anchorRepository, claimRepository, personDirectory)

    fun anchor(type: AttributeType, value: String, accountId: AccountId) {
        every { anchorRepository.findByAttributeTypeAndValue(type, value) } returns
            AccountAnchor(attributeType = type, value = value, accountId = accountId, establishedAt = TEST_NOW)
    }
}

private fun accountClaim(type: AttributeType, value: String, source: ClaimSource) = AccountClaim(
    accountId = AccountId(1L), attributeType = type, value = value, claimSource = source.value, establishedAt = TEST_NOW
)
