package com.example.identity.core.account

import com.example.identity.tools.ident_nect.NECT_RESTRICTED_ID
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.directory.resolveAccountByPersonId
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.application.AnchorRegistry
import com.example.identity.core.account.application.ChangeLog
import com.example.identity.core.account.application.ClaimLedger
import com.example.identity.core.account.application.PersonLookupKey
import com.example.identity.core.account.infrastructure.Account
import com.example.identity.core.account.infrastructure.AccountAnchor
import com.example.identity.core.account.infrastructure.AccountAnchorRepository
import com.example.identity.core.account.infrastructure.AccountAuthMethod
import com.example.identity.core.account.infrastructure.AccountAuthMethodRepository
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.AccountClaimRepository
import com.example.identity.core.account.infrastructure.AccountRepository
import com.example.identity.core.account.infrastructure.AccountRetraction
import com.example.identity.core.account.infrastructure.AccountRetractionRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID

/**
 * Unit test of the per-call decisions: every claim lands in `account.claim`; local anchors such as
 * PERSON_ID and EMAIL also go into `account.anchor`, where `AnchorRule` decides replacement and the
 * ACR floor. [AccountServiceDbTest] pins what needs the real schema: transactions and unique
 * constraints. Each `when` builds its own [AccountServiceFixture], so no call count leaks between them.
 */
class AccountServiceTest : BehaviorSpec({

    val ownAccount = AccountId(7L)

    given("an unidentified account") {
        `when`("a person_id from the register is recorded from a loa2 session") {
            val fixture = AccountServiceFixture(ownAccount)
            fixture.service.recordClaim(
                accountId = ownAccount,
                claim = Claim(AttributeType.PERSON_ID, "P000000042", ClaimSource.PERSON_DIRECTORY, AcrLevel.LOA2),
                provenAcr = AcrLevel.LOA2
            )

            then("the claim lands in the log") {
                val claim = fixture.savedClaims.single()
                claim.accountId shouldBe ownAccount
                claim.attributeType shouldBe AttributeType.PERSON_ID
                claim.value shouldBe "P000000042"
                claim.claimSource shouldBe "person_directory"
                claim.establishedAcr shouldBe "loa2"
                claim.establishedAt.shouldNotBeNull()
            }

            then("its anchor consolidates") {
                val anchor = fixture.savedAnchors.single()
                anchor.attributeType shouldBe AttributeType.PERSON_ID
                anchor.value shouldBe "P000000042"
                anchor.accountId shouldBe ownAccount
            }
        }

        // ADR-5's line applied to anchors: a write is priced by what the session actually proved,
        // not by what the asserting tool declares for itself.
        `when`("a person_id declared at loa2 is recorded from a session that only proved loa1") {
            val fixture = AccountServiceFixture(ownAccount)
            val result = runCatching {
                fixture.service.recordClaim(
                    ownAccount,
                    Claim(AttributeType.PERSON_ID, "P000004711", ClaimSource.PERSON_DIRECTORY, AcrLevel.LOA2),
                    provenAcr = AcrLevel.LOA1
                )
            }

            then("establishing a person_id below its floor is refused, and nothing is anchored") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
                fixture.savedAnchors.shouldBeEmpty()
            }
        }

        `when`("an email is recorded in another spelling") {
            val fixture = AccountServiceFixture(ownAccount)
            fixture.service.recordClaim(
                accountId = ownAccount,
                claim = Claim(AttributeType.EMAIL, "  Max@Example.COM ", ClaimSource.SELF_REPORTED, AcrLevel.LOA1),
                provenAcr = AcrLevel.LOA2
            )

            then("the claim is logged raw") {
                fixture.savedClaims.single().value shouldBe "  Max@Example.COM "
            }

            then("its anchor materializes normalized") {
                val anchor = fixture.savedAnchors.single()
                anchor.accountId shouldBe ownAccount
                anchor.attributeType shouldBe AttributeType.EMAIL
                anchor.value shouldBe "max@example.com"
                anchor.establishedAt.shouldNotBeNull()
            }
        }
    }

    given("an account whose email was established at loa1") {
        `when`("a new email is recorded from a loa1 session") {
            val fixture = AccountServiceFixture(ownAccount)
            val anchor = fixture.holds(ownAccount, AttributeType.EMAIL, "first@example.com", establishedAcr = AcrLevel.LOA1)
            val result = runCatching {
                fixture.service.recordClaim(ownAccount, Claim(AttributeType.EMAIL, "second@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA1)
            }

            then("replacing an email costs loa2 even though establishing it cost loa1") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
                fixture.savedAnchors.shouldBeEmpty()
                anchor.value shouldBe "first@example.com"
            }
        }
    }

    // docs/02-domaenenmodell.md Abschnitt 6: a new member number or a new card is a new binding, priced like the first.
    for ((type, first, second) in listOf(
        Triple(AttributeType.MEMBER_NUMBER, "10000001", "10000002"),
        Triple(NECT_RESTRICTED_ID, "N0103005K1D5S0V8T9W6UM2RTX", "N0909090Z9X8Y7W6V5U4T3S2R1")
    )) {
        val source = if (type == AttributeType.MEMBER_NUMBER) ClaimSource.PERSON_DIRECTORY else ClaimSource("ident-nect")

        given("an account without a ${type.wireName}") {
            `when`("a ${type.wireName} is recorded from a loa1 session") {
                val fixture = AccountServiceFixture(ownAccount)
                val result = runCatching { fixture.service.recordClaim(ownAccount, Claim(type, first, source), provenAcr = AcrLevel.LOA1) }

                then("binding it below loa2 is refused, and nothing is anchored") {
                    shouldThrow<IdentityConflictException> { result.getOrThrow() }
                    fixture.savedAnchors.shouldBeEmpty()
                }
            }

            `when`("a ${type.wireName} is recorded from a loa2 session") {
                val fixture = AccountServiceFixture(ownAccount)
                fixture.service.recordClaim(ownAccount, Claim(type, first, source), provenAcr = AcrLevel.LOA2)

                then("it is anchored") {
                    fixture.savedAnchors.single().value shouldBe first
                }
            }
        }

        given("an account bound to a ${type.wireName}") {
            `when`("a new ${type.wireName} is recorded from a loa1 session") {
                val fixture = AccountServiceFixture(ownAccount)
                val anchor = fixture.holds(ownAccount, type, first)
                val result = runCatching { fixture.service.recordClaim(ownAccount, Claim(type, second, source), provenAcr = AcrLevel.LOA1) }

                then("replacing it below loa2 is refused, and the anchor keeps its value") {
                    shouldThrow<IdentityConflictException> { result.getOrThrow() }
                    fixture.savedAnchors.shouldBeEmpty()
                    anchor.value shouldBe first
                }
            }

            `when`("a new ${type.wireName} is recorded from a loa2 session") {
                val fixture = AccountServiceFixture(ownAccount)
                val anchor = fixture.holds(ownAccount, type, first)
                fixture.service.recordClaim(ownAccount, Claim(type, second, source), provenAcr = AcrLevel.LOA2)

                then("the anchor is replaced in place") {
                    anchor.value shouldBe second
                }
            }
        }
    }

    given("an account already bound to a person_id") {
        fun fixture() = AccountServiceFixture(ownAccount).apply {
            holds(ownAccount, AttributeType.PERSON_ID, "P000000042")
        }

        `when`("the same person_id is asserted again") {
            val fixture = fixture()
            fixture.service.recordClaim(ownAccount, Claim(AttributeType.PERSON_ID, "P000000042", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)

            then("it is idempotent - no new anchor row, no rejection") {
                verify(exactly = 0) { fixture.anchorRepository.save(any()) }
                verify(exactly = 0) { fixture.anchorRepository.delete(any()) }
            }
        }

        `when`("a different person_id is asserted for the same account") {
            val fixture = fixture()
            val result = runCatching {
                fixture.service.recordClaim(ownAccount, Claim(AttributeType.PERSON_ID, "P000000099", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)
            }

            then("it is rejected - person_id is immutable after first binding (docs/02-domaenenmodell.md Abschnitt 6)") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
                verify(exactly = 0) { fixture.anchorRepository.delete(any()) }
                verify(exactly = 0) { fixture.anchorRepository.save(any()) }
            }
        }

        `when`("the holder withdraws the person_id") {
            val fixture = fixture()
            val result = runCatching {
                fixture.service.retractAttribute(ownAccount, AttributeType.PERSON_ID, RetractionSource.ACCOUNT_HOLDER)
            }

            then("the account module itself refuses it, whatever the caller checked") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
                fixture.savedRetractions.shouldBeEmpty()
                verify(exactly = 0) { fixture.anchorRepository.delete(any()) }
            }
        }
    }

    given("no account exists yet") {
        `when`("an account is created before its claims are accepted") {
            val fixture = AccountServiceFixture(ownAccount)
            val profile = fixture.service.createAccountInSetup()

            then("the new account has no direct person binding") {
                profile.personId shouldBe null
                verify(exactly = 1) { fixture.accountRepository.save(any()) }
            }
        }
    }

    given("an account with a login method, holding email and person_id anchors") {
        fun fixture() = AccountServiceFixture(ownAccount).apply {
            holds(ownAccount, AttributeType.EMAIL, "max@example.com")
            holds(ownAccount, AttributeType.PERSON_ID, "P000000042")
        }

        `when`("resolving an account by the email in another spelling") {
            val fixture = fixture()
            val resolved = fixture.service.resolveByAnchor(AttributeType.EMAIL, "  Max@Example.COM ")

            then("the lookup runs normalized and finds the account") {
                resolved shouldBe ownAccount
            }

            then("only whether a login method exists is read (ADR-46), never the account itself") {
                verify(exactly = 0) { fixture.accountRepository.findAccount(any()) }
                verify(exactly = 0) { fixture.accountRepository.findForUpdate(any()) }
            }
        }

        `when`("resolving an account by the person_id") {
            val fixture = fixture()
            val resolved = fixture.service.resolveAccountByPersonId(PartnerNumber("P000000042"))

            then("it finds the account without loading it") {
                resolved shouldBe ownAccount
                verify(exactly = 0) { fixture.accountRepository.findAccount(any()) }
            }
        }

        `when`("resolving an account by an email nobody holds") {
            val fixture = fixture()
            val resolved = fixture.service.resolveByAnchor(AttributeType.EMAIL, "other@example.com")

            then("there is none") {
                resolved.shouldBeNull()
            }
        }

        `when`("finding the account by email") {
            val fixture = fixture()
            val profile = fixture.service.findAccountByEmail("max@example.com")

            then("its profile comes back") {
                profile?.accountId shouldBe ownAccount
            }
        }

        `when`("finding the account by person_id") {
            val fixture = fixture()
            val profile = fixture.service.findAccountByPersonId(PartnerNumber("P000000042"))

            then("its profile comes back") {
                profile?.accountId shouldBe ownAccount
            }
        }

        `when`("finding an account by an email nobody holds") {
            val fixture = fixture()
            val profile = fixture.service.findAccountByEmail("missing@example.com")

            then("there is none") {
                profile.shouldBeNull()
            }
        }

        `when`("finding an account by a person_id nobody holds") {
            val fixture = fixture()
            val profile = fixture.service.findAccountByPersonId(PartnerNumber("P000000099"))

            then("there is none") {
                profile.shouldBeNull()
            }
        }

        `when`("reading the account's email anchor value") {
            val fixture = fixture()
            val value = fixture.service.anchorValue(ownAccount, AttributeType.EMAIL)

            then("it is the stored normalized value") {
                value shouldBe "max@example.com"
            }
        }

        `when`("reading another account's email anchor value") {
            val fixture = fixture()
            val value = fixture.service.anchorValue(AccountId(8L), AttributeType.EMAIL)

            then("there is none") {
                value.shouldBeNull()
            }
        }
    }

    given("an account still being set up, holding an email anchor") {
        `when`("resolving an account by that email") {
            val fixture = AccountServiceFixture(ownAccount).apply {
                holds(ownAccount, AttributeType.EMAIL, "max@example.com")
                every { authMethodRepository.existsByAccountId(ownAccount) } returns false
            }
            val resolved = fixture.service.resolveByAnchor(AttributeType.EMAIL, "max@example.com")

            then("it is not found (ADR-46)") {
                resolved.shouldBeNull()
            }
        }
    }

    // KVNR changes follow the Personenverzeichnis without creating or reading a local KVNR anchor.
    given("an account bound to person P000000042, whose KVNR the register maps") {
        fun fixture() = AccountServiceFixture(ownAccount).apply {
            holds(ownAccount, AttributeType.PERSON_ID, "P000000042")
        }
        fun register(vararg kvnrToPerson: Pair<String, String>) = mockk<PersonDirectory> {
            every { findPersonIdByKvnr(any()) } returns null
            kvnrToPerson.forEach { (kvnr, personId) -> every { findPersonIdByKvnr(kvnr) } returns PartnerNumber(personId) }
        }

        `when`("finding the account by its KVNR in another spelling") {
            val fixture = fixture()
            val profile = fixture.service.findAccountByKvnr(" a123456789 ", register("A123456789" to "P000000042"))

            then("the register's current mapping finds it, without a local KVNR anchor") {
                profile?.accountId shouldBe ownAccount
                verify(exactly = 0) { fixture.anchorRepository.findByAttributeTypeAndValue(AttributeType.KVNR, any()) }
            }
        }

        `when`("finding the account by the KVNR the register has since moved away") {
            val fixture = fixture()
            val profile = fixture.service.findAccountByKvnr("A123456789", register("B987654321" to "P000000042"))

            then("there is none") {
                profile.shouldBeNull()
            }
        }

        `when`("finding the account by the KVNR the register moved to it") {
            val fixture = fixture()
            val profile = fixture.service.findAccountByKvnr("B987654321", register("B987654321" to "P000000042"))

            then("it is found") {
                profile?.accountId shouldBe ownAccount
            }
        }

        `when`("resolving an account by a KVNR anchor") {
            val fixture = fixture()
            val result = runCatching { fixture.service.resolveByAnchor(AttributeType.KVNR, "A123456789") }

            then("it is refused - KVNR is no local anchor") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("reading the account's KVNR anchor value") {
            val fixture = fixture()
            val result = runCatching { fixture.service.anchorValue(ownAccount, AttributeType.KVNR) }

            then("it is refused - KVNR is no local anchor") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }

        `when`("recording a KVNR claim") {
            val fixture = fixture()
            fixture.service.recordClaim(ownAccount, Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)

            then("it records provenance only, not a local binding") {
                fixture.savedClaims.single().attributeType shouldBe AttributeType.KVNR
                verify(exactly = 0) { fixture.anchorRepository.save(any()) }
                verify(exactly = 0) { fixture.accountRepository.save(any()) }
            }
        }
    }

    given("an unidentified account with an enrolled login method") {
        `when`("it is to be absorbed into another account") {
            val fixture = AccountServiceFixture(ownAccount).apply { enrolled(passwordMethod(ownAccount)) }
            val result = runCatching { fixture.service.absorbDisposableAccount(ownAccount, AccountId(8L)) }

            then("it never yields - a credential was enrolled on it, so this would be an account merge") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
                verify(exactly = 0) { fixture.accountRepository.deleteAccount(any()) }
            }
        }
    }

    given("an unidentified account whose only login method is active") {
        `when`("the holder deactivates that method") {
            val method = passwordMethod(ownAccount)
            val fixture = AccountServiceFixture(ownAccount).apply { enrolled(method) }
            val profile = fixture.service.deactivateAuthenticationMethod(ownAccount, method.id.toString())

            then("a deactivated credential still counts - its claims' provenance points at this account") {
                profile.activeAuthenticationMethods.shouldBeEmpty()
                profile.authenticationMethods shouldHaveSize 1
                profile.isDisposable shouldBe false
            }
        }
    }
})

/**
 * The service over mocked repositories that start out empty, with the real [ClaimLedger] and
 * [AnchorRegistry] in between: these tests are about what gets written, so the two parts stay real.
 * [accountId] exists and has a login method.
 */
private class AccountServiceFixture(accountId: AccountId) {
    val accountRepository = mockk<AccountRepository>()
    val claimRepository = mockk<AccountClaimRepository>()
    val anchorRepository = mockk<AccountAnchorRepository>()
    val authMethodRepository = mockk<AccountAuthMethodRepository>()
    val retractionRepository = mockk<AccountRetractionRepository>()
    private val changeLog = mockk<ChangeLog>(relaxed = true)

    val savedClaims = mutableListOf<AccountClaim>()
    val savedAnchors = mutableListOf<AccountAnchor>()
    val savedRetractions = mutableListOf<AccountRetraction>()
    private val anchors = mutableListOf<AccountAnchor>()

    val service: AccountService

    init {
        val account = Account(createdAt = TEST_NOW).apply { id = accountId.value }
        every { accountRepository.findAccount(accountId) } returns account
        every { accountRepository.findForUpdate(accountId) } returns account
        every { accountRepository.save(any()) } answers { firstArg<Account>().apply { id = accountId.value } }

        every { claimRepository.findEstablished(any()) } returns emptyList()
        every { claimRepository.save(capture(savedClaims)) } answers { firstArg() }

        every { anchorRepository.findByAttributeTypeAndValue(any(), any()) } returns null
        every { anchorRepository.findByAccountIdAndAttributeType(any(), any()) } returns null
        every { anchorRepository.findByAccountId(any()) } answers { anchors.filter { it.accountId == firstArg() } }
        every { anchorRepository.save(capture(savedAnchors)) } answers { firstArg() }
        every { anchorRepository.delete(any()) } just runs

        every { authMethodRepository.existsByAccountId(any()) } returns true
        every { authMethodRepository.findByAccountIdOrderByCreatedAt(any()) } returns emptyList()

        // A relaxed mock cannot answer the generic save(S): its fabricated return fails the cast.
        every { retractionRepository.save(capture(savedRetractions)) } answers { firstArg() }

        val ledger = ClaimLedger(claimRepository, retractionRepository, changeLog, clock = TEST_CLOCK)
        service = AccountService(
            accountRepository, ledger, AnchorRegistry(anchorRepository, ledger), authMethodRepository,
            mockk<ApplicationEventPublisher>(relaxed = true), changeLog, mockk<PersonLookupKey>(relaxed = true), TEST_CLOCK
        )
    }

    /** [holder] holds [value] as its [type] anchor. */
    fun holds(holder: AccountId, type: AttributeType, value: String, establishedAcr: AcrLevel = AcrLevel.LOA2): AccountAnchor {
        val anchor = AccountAnchor(attributeType = type, value = value, accountId = holder, establishedAcr = establishedAcr.value, establishedAt = TEST_NOW)
        anchors += anchor
        every { anchorRepository.findByAttributeTypeAndValue(type, value) } returns anchor
        every { anchorRepository.findByAccountIdAndAttributeType(holder, type) } returns anchor
        return anchor
    }

    /** [method] is the account's only login method, found by its id. */
    fun enrolled(method: AccountAuthMethod) {
        val accountId = checkNotNull(method.accountId)
        every { authMethodRepository.findByAccountIdOrderByCreatedAt(accountId) } returns listOf(method)
        every { authMethodRepository.findByIdAndAccountId(checkNotNull(method.id), accountId) } returns method
    }
}

private fun passwordMethod(accountId: AccountId) = AccountAuthMethod(
    accountId = accountId, method = "password", enrollmentType = "auth_password", enrollmentId = "e-1", enrolledUnderAcr = "loa1"
).apply { id = UUID.randomUUID(); createdAt = TEST_NOW }
