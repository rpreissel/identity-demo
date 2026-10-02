package com.example.identity.contract.tool_api.directory

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.claims.AttributeType
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.confirmVerified
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

class AccountDirectoryExtensionsTest : BehaviorSpec({

    given("an account anchored by an email address") {
        val directory = mockk<AccountDirectory>()
        every { directory.resolveByAnchor(AttributeType.EMAIL, "  Max@Example.COM ") } returns AccountId(7L)

        `when`("the account is resolved by that address, as typed") {
            val accountId = directory.resolveAccountByEmail("  Max@Example.COM ")

            then("it delegates once to the generic anchor lookup, which owns normalization") {
                accountId shouldBe AccountId(7)
                verify(exactly = 1) { directory.resolveByAnchor(AttributeType.EMAIL, "  Max@Example.COM ") }
                confirmVerified(directory)
            }
        }
    }

    given("an account anchored by a person ID") {
        val directory = mockk<AccountDirectory>()
        every { directory.resolveByAnchor(AttributeType.PERSON_ID, "P000000042") } returns AccountId(7L)

        `when`("the account is resolved by that person ID") {
            val accountId = directory.resolveAccountByPersonId(PartnerNumber("P000000042"))

            then("it delegates once using the canonical value") {
                accountId shouldBe AccountId(7)
                verify(exactly = 1) { directory.resolveByAnchor(AttributeType.PERSON_ID, "P000000042") }
                confirmVerified(directory)
            }
        }
    }

    given("no local anchors at all") {
        val directory = mockk<AccountDirectory>()
        every { directory.resolveByAnchor(any(), any()) } returns null

        `when`("an account is resolved by email") {
            val accountId = directory.resolveAccountByEmail("missing@example.com")

            then("there is none") {
                accountId shouldBe null
            }
        }

        `when`("an account is resolved by person ID") {
            val accountId = directory.resolveAccountByPersonId(PartnerNumber("P000000042"))

            then("there is none") {
                accountId shouldBe null
            }
        }
    }

    given("a KVNR the master data knows, and an account anchored by that person's ID") {
        val directory = mockk<AccountDirectory>()
        val persons = mockk<PersonDirectory>()
        every { persons.findPersonIdByKvnr("A123456789") } returns PartnerNumber("P000000042")
        every { directory.resolveByAnchor(AttributeType.PERSON_ID, "P000000042") } returns AccountId(7L)

        `when`("the account is resolved by the KVNR, as typed") {
            val accountId = directory.resolveAccountByKvnr(" a123456789 ", persons)

            then("it follows current master data to the person-ID anchor") {
                accountId shouldBe AccountId(7)
                verify(exactly = 1) { persons.findPersonIdByKvnr("A123456789") }
                verify(exactly = 1) { directory.resolveByAnchor(AttributeType.PERSON_ID, "P000000042") }
                confirmVerified(persons, directory)
            }
        }
    }

    given("a KVNR the master data does not know") {
        val directory = mockk<AccountDirectory>()
        val persons = mockk<PersonDirectory>()
        every { persons.findPersonIdByKvnr("A123456789") } returns null

        `when`("an account is resolved by it") {
            val accountId = directory.resolveAccountByKvnr("A123456789", persons)

            then("there is none, and local account data is not consulted") {
                accountId shouldBe null
                verify { directory wasNot Called }
            }
        }
    }

    given("a KVNR of a known person without an account") {
        val directory = mockk<AccountDirectory>()
        val persons = mockk<PersonDirectory>()
        every { persons.findPersonIdByKvnr("A123456789") } returns PartnerNumber("P000000042")
        every { directory.resolveByAnchor(AttributeType.PERSON_ID, "P000000042") } returns null

        `when`("an account is resolved by it") {
            val accountId = directory.resolveAccountByKvnr("A123456789", persons)

            then("there is none") {
                accountId shouldBe null
            }
        }
    }
})
