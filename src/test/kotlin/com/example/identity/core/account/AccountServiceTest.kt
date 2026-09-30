package com.example.identity.core.account

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.core.account.application.PersonLookupKey
import com.example.identity.core.account.application.ChangeLog
import com.example.identity.core.account.infrastructure.AccountAuthMethodRepository
import com.example.identity.core.account.application.AnchorRegistry
import com.example.identity.core.account.application.ClaimLedger
import com.example.identity.core.account.infrastructure.Account
import com.example.identity.core.account.infrastructure.AccountAnchor
import com.example.identity.core.account.infrastructure.AccountAnchorRepository
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.AccountClaimRepository
import com.example.identity.core.account.infrastructure.AccountRepository
import com.example.identity.core.account.infrastructure.AccountRetractionRepository
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.directory.resolveAccountByEmail
import com.example.identity.contract.tool_api.directory.resolveAccountByPersonId
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.repository.findByIdOrNull

/**
 * Unit test of the claims-log write: every claim lands in `account.claim`; PERSON_ID and EMAIL
 * also go into `account.anchor`, where `AnchorRule.allowsReplacement` makes the difference.
 * Mocks are created per `given` block so call counts of one scenario do not leak into another.
 */
class AccountServiceTest : BehaviorSpec({

    given("an unidentified account receiving a person_id claim") {
        val accountRepository = mockk<AccountRepository>()
        val accountClaimRepository = mockk<AccountClaimRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountClaimRepository, accountAnchorRepository, mockk(relaxed = true), mockk(relaxed = true), eventPublisher, mockk(relaxed = true), mockk(relaxed = true))

        val account = Account(createdAt = TEST_NOW).apply { id = 7L }
        every { accountRepository.findAccount(AccountId(7)) } returns account
        every { accountRepository.findForUpdate(AccountId(7)) } returns account
        every { accountAnchorRepository.findByAccountIdAndAttributeType(AccountId(7L), AttributeType.PERSON_ID) } returns null
        every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, any()) } returns null
        every { accountClaimRepository.findEstablished(any()) } returns emptyList()

        `when`("recording a claim from an identifying tool") {
            val savedClaims = mutableListOf<AccountClaim>()
            every { accountClaimRepository.save(capture(savedClaims)) } answers { savedClaims.last() }
            val savedAnchors = mutableListOf<AccountAnchor>()
            every { accountAnchorRepository.save(capture(savedAnchors)) } answers { savedAnchors.last() }

            service.recordClaim(
                accountId = AccountId(7L),
                claim = Claim(AttributeType.PERSON_ID, "P000000042", ClaimSource.PERSON_DIRECTORY, AcrLevel.LOA2),
                provenAcr = AcrLevel.LOA2
            )

            then("the claim lands in the log and its anchor consolidates") {
                savedClaims shouldHaveSize 1
                savedClaims.single().accountId shouldBe AccountId(7)
                savedClaims.single().attributeType shouldBe AttributeType.PERSON_ID
                savedClaims.single().value shouldBe "P000000042"
                savedClaims.single().claimSource shouldBe "person_directory"
                savedClaims.single().establishedAcr shouldBe "loa2"
                savedClaims.single().establishedAt.shouldNotBeNull()
                savedAnchors shouldHaveSize 1
                savedAnchors.single().attributeType shouldBe AttributeType.PERSON_ID
                savedAnchors.single().value shouldBe "P000000042"
                savedAnchors.single().accountId shouldBe AccountId(7)
            }
        }
    }

    given("an account already bound to a person_id") {
        val accountRepository = mockk<AccountRepository>()
        val accountClaimRepository = mockk<AccountClaimRepository>(relaxed = true)
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountClaimRepository, accountAnchorRepository, mockk(relaxed = true), mockk(relaxed = true), eventPublisher, mockk(relaxed = true), mockk(relaxed = true))

        val account = Account(createdAt = TEST_NOW).apply { id = 7L }
        every { accountRepository.findAccount(AccountId(7)) } returns account
        every { accountRepository.findForUpdate(AccountId(7)) } returns account
        every { accountClaimRepository.save(any()) } answers { firstArg() }
        val existingAnchor = AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "P000000042", accountId = AccountId(7L), establishedAt = TEST_NOW)

        `when`("re-asserting the same person_id") {
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000042") } returns existingAnchor

            then("it is idempotent - no new anchor row, no rejection") {
                service.recordClaim(AccountId(7L), Claim(AttributeType.PERSON_ID, "P000000042", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)
                verify(exactly = 0) { accountAnchorRepository.save(any()) }
                verify(exactly = 0) { accountAnchorRepository.delete(any()) }
            }
        }

        `when`("asserting a DIFFERENT person_id for the same account") {
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000099") } returns null
            every { accountAnchorRepository.findByAccountIdAndAttributeType(AccountId(7L), AttributeType.PERSON_ID) } returns existingAnchor

            then("it is rejected - person_id is immutable after first binding (docs/02-domaenenmodell.md Abschnitt 6)") {
                shouldThrow<IdentityConflictException> {
                    service.recordClaim(AccountId(7L), Claim(AttributeType.PERSON_ID, "P000000099", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)
                }
                verify(exactly = 0) { accountAnchorRepository.delete(any()) }
                verify(exactly = 0) { accountAnchorRepository.save(any()) }
            }
        }
    }

    given("no account exists yet") {
        val accountRepository = mockk<AccountRepository>()
        val accountClaimRepository = mockk<AccountClaimRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val service = AccountService(accountRepository, accountClaimRepository, accountAnchorRepository, mockk(relaxed = true), mockk(relaxed = true), eventPublisher, mockk(relaxed = true), mockk(relaxed = true))

        every { accountRepository.save(any()) } answers { firstArg<Account>().apply { id = 7L } }

        `when`("creating an account before accepting its claims") {
            val profile = service.createAccountInSetup()

            then("the new account has no direct person binding") {
                profile.personId shouldBe null
                verify(exactly = 1) { accountRepository.save(any()) }
            }
        }
    }

    given("an account with an anchor-role claim") {
        val accountRepository = mockk<AccountRepository>()
        val accountClaimRepository = mockk<AccountClaimRepository>()
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        // A relaxed mock cannot answer the generic save(S): its fabricated return fails the cast.
        val accountRetractionRepository = mockk<AccountRetractionRepository>()
        every { accountRetractionRepository.save(any()) } answers { firstArg() }
        val service = AccountService(accountRepository, accountClaimRepository, accountAnchorRepository, mockk(relaxed = true), accountRetractionRepository, eventPublisher, mockk(relaxed = true), mockk(relaxed = true))

        val account = Account(createdAt = TEST_NOW).apply { id = 7L }
        every { accountRepository.findAccount(AccountId(7)) } returns account
        every { accountRepository.findForUpdate(AccountId(7)) } returns account

        val savedClaims = mutableListOf<AccountClaim>()
        every { accountClaimRepository.save(capture(savedClaims)) } answers { savedClaims.last() }
        val savedAnchors = mutableListOf<AccountAnchor>()
        every { accountAnchorRepository.save(capture(savedAnchors)) } answers { savedAnchors.last() }
        every { accountAnchorRepository.findByAccountIdAndAttributeType(AccountId(7L), AttributeType.EMAIL) } returns null
        every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, any()) } returns null
        every { accountClaimRepository.findEstablished(any()) } returns emptyList()

        `when`("recording an email claim") {
            service.recordClaim(
                accountId = AccountId(7L),
                claim = Claim(AttributeType.EMAIL, "  Max@Example.COM ", ClaimSource.SELF_REPORTED, AcrLevel.LOA1),
                provenAcr = AcrLevel.LOA2
            )

            then("the claim is logged raw, its anchor materializes normalized") {
                savedClaims.single().value shouldBe "  Max@Example.COM "
                savedAnchors shouldHaveSize 1
                savedAnchors.single().accountId shouldBe AccountId(7)
                savedAnchors.single().attributeType shouldBe AttributeType.EMAIL
                savedAnchors.single().value shouldBe "max@example.com"
                savedAnchors.single().establishedAt.shouldNotBeNull()
            }
        }

        `when`("recording an anchor another account already holds") {
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "other@example.com") } returns
                AccountAnchor(attributeType = AttributeType.EMAIL, value = "other@example.com", accountId = AccountId(99L), establishedAt = TEST_NOW)

            then("the claim is rejected instead of silently skipping the anchor (ADR-11)") {
                shouldThrow<IdentityConflictException> {
                    service.recordClaim(
                        accountId = AccountId(7L),
                        claim = Claim(AttributeType.EMAIL, "other@example.com", ClaimSource.SELF_REPORTED, AcrLevel.LOA1),
                        provenAcr = AcrLevel.LOA2
                    )
                }
                // No second anchor. Rolling back the appended log row is the transaction's job,
                // so the claim count still moves here.
                savedAnchors shouldHaveSize 1
                savedClaims shouldHaveSize 2
            }
        }

        `when`("re-binding this account's own anchor to a new value") {
            val oldAnchor = AccountAnchor(attributeType = AttributeType.EMAIL, value = "old@example.com", accountId = AccountId(7L), establishedAt = TEST_NOW)
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "new@example.com") } returns null
            every { accountAnchorRepository.findByAccountIdAndAttributeType(AccountId(7L), AttributeType.EMAIL) } returns oldAnchor

            service.recordClaim(
                accountId = AccountId(7L),
                claim = Claim(AttributeType.EMAIL, "new@example.com", ClaimSource.SELF_REPORTED, AcrLevel.LOA1),
                provenAcr = AcrLevel.LOA2
            )

            then("the existing row is UPDATED in place, not deleted and re-inserted (real unique-constraint ordering, docs/02-domaenenmodell.md Abschnitt 6)") {
                verify(exactly = 0) { accountAnchorRepository.delete(any()) }
                // savedAnchors is shared with the first `when`; the rebind saves the mutated
                // oldAnchor instance, not a fresh AccountAnchor.
                savedAnchors.last() shouldBe oldAnchor
                oldAnchor.value shouldBe "new@example.com"
            }
        }
    }

    given("ID lookups on the concrete account service") {
        then("they do not load an account or build a profile") {
            val accounts = mockk<AccountRepository>()
            val anchors = mockk<AccountAnchorRepository>()
            val authMethods = mockk<AccountAuthMethodRepository>()
            val service = AccountService(accounts, mockk(), anchors, authMethods, mockk(), mockk(), mockk(relaxed = true), mockk(relaxed = true))
            every { anchors.findByAttributeTypeAndValue(AttributeType.EMAIL, "max@example.com") } returns
                AccountAnchor(attributeType = AttributeType.EMAIL, value = "max@example.com", accountId = AccountId(7L), establishedAt = TEST_NOW)
            every { anchors.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000042") } returns
                AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "P000000042", accountId = AccountId(7L), establishedAt = TEST_NOW)
            every { authMethods.existsByAccountId(AccountId(7L)) } returns true
            service.resolveAccountByEmail("  Max@Example.COM ") shouldBe AccountId(7)
            service.resolveAccountByPersonId(PartnerNumber("P000000042")) shouldBe AccountId(7)
            // Only whether a login method exists (ADR-46), never the account itself.
            verify(exactly = 0) { accounts.findAccount(any()) }
            verify(exactly = 0) { accounts.findForUpdate(any()) }
        }
    }

    given("the anchor read ports") {
        val accountRepository = mockk<AccountRepository>()
        // Relaxed: toProfile also reads the claim log, which these anchor cases do not exercise.
        val accountClaimRepository = mockk<AccountClaimRepository>(relaxed = true)
        val accountAnchorRepository = mockk<AccountAnchorRepository>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val accountAuthMethodRepository = mockk<AccountAuthMethodRepository>(relaxed = true)
        val service = AccountService(accountRepository, accountClaimRepository, accountAnchorRepository, accountAuthMethodRepository, mockk(relaxed = true), eventPublisher, mockk(relaxed = true), mockk(relaxed = true))

        then("KVNR changes follow personenverzeichnis without creating or reading a local KVNR anchor") {
            val persons = mockk<PersonDirectory>()
            val account = Account(createdAt = TEST_NOW).apply { id = 7L }
            every { accountRepository.findAccount(AccountId(7)) } returns account
            every { accountAuthMethodRepository.existsByAccountId(AccountId(7L)) } returns true
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000042") } returns
                AccountAnchor(attributeType = AttributeType.PERSON_ID, value = "P000000042", accountId = AccountId(7L), establishedAt = TEST_NOW)
            every { persons.findPersonIdByKvnr("A123456789") } returns PartnerNumber("P000000042")
            service.findAccountByKvnr(" a123456789 ", persons)?.accountId shouldBe AccountId(7)

            every { persons.findPersonIdByKvnr("A123456789") } returns null
            every { persons.findPersonIdByKvnr("B987654321") } returns PartnerNumber("P000000042")
            service.findAccountByKvnr("A123456789", persons) shouldBe null
            service.findAccountByKvnr("B987654321", persons)?.accountId shouldBe AccountId(7)
            verify(exactly = 0) { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.KVNR, any()) }
            shouldThrow<IllegalStateException> { service.resolveByAnchor(AttributeType.KVNR, "A123456789") }
            shouldThrow<IllegalStateException> { service.anchorValue(AccountId(7L), AttributeType.KVNR) }
        }

        then("a KVNR claim records provenance only, not a local binding") {
            every { accountClaimRepository.save(any()) } answers { firstArg() }
            service.recordClaim(AccountId(7L), Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)
            verify(exactly = 1) { accountClaimRepository.save(match { it.attributeType == AttributeType.KVNR }) }
            verify(exactly = 0) { accountAnchorRepository.save(any()) }
            verify(exactly = 0) { accountRepository.save(any()) }
        }

        then("typed extensions resolve both person ID and email through anchors") {
            val account = Account(createdAt = TEST_NOW).apply { id = 7L }
            every { accountRepository.findAccount(AccountId(7)) } returns account
            every { accountAuthMethodRepository.existsByAccountId(AccountId(7L)) } returns true
            for ((type, value) in listOf(AttributeType.EMAIL to "max@example.com", AttributeType.PERSON_ID to "P000000042")) {
                every { accountAnchorRepository.findByAttributeTypeAndValue(type, value) } returns
                    AccountAnchor(attributeType = type, value = value, accountId = AccountId(7L), establishedAt = TEST_NOW)
            }
            service.findAccountByEmail("  Max@Example.COM ")?.accountId shouldBe AccountId(7)
            service.findAccountByPersonId(PartnerNumber("P000000042"))?.accountId shouldBe AccountId(7)
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "missing@example.com") } returns null
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.PERSON_ID, "P000000099") } returns null
            service.findAccountByEmail("missing@example.com") shouldBe null
            service.findAccountByPersonId(PartnerNumber("P000000099")) shouldBe null
        }

        `when`("resolving an account by anchor") {
            every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "max@example.com") } returns
                AccountAnchor(attributeType = AttributeType.EMAIL, value = "max@example.com", accountId = AccountId(7L), establishedAt = TEST_NOW)

            then("the lookup runs normalized") {
                every { accountAuthMethodRepository.existsByAccountId(AccountId(7L)) } returns true
                service.resolveByAnchor(AttributeType.EMAIL, "  Max@Example.COM ") shouldBe AccountId(7)
                every { accountAnchorRepository.findByAttributeTypeAndValue(AttributeType.EMAIL, "other@example.com") } returns null
                service.resolveByAnchor(AttributeType.EMAIL, "other@example.com") shouldBe null
            }

            then("an account still being set up is not found (ADR-46)") {
                every { accountAuthMethodRepository.existsByAccountId(AccountId(7L)) } returns false
                service.resolveByAnchor(AttributeType.EMAIL, "max@example.com") shouldBe null
            }
        }

        `when`("reading an account's anchor value") {
            every { accountAnchorRepository.findByAccountIdAndAttributeType(AccountId(7L), AttributeType.EMAIL) } returns
                AccountAnchor(attributeType = AttributeType.EMAIL, value = "max@example.com", accountId = AccountId(7L), establishedAt = TEST_NOW)

            then("it returns the stored normalized value") {
                service.anchorValue(AccountId(7L), AttributeType.EMAIL) shouldBe "max@example.com"
                every { accountAnchorRepository.findByAccountIdAndAttributeType(AccountId(8L), AttributeType.EMAIL) } returns null
                service.anchorValue(AccountId(8L), AttributeType.EMAIL) shouldBe null
            }
        }
    }
})

/**
 * The service over mocked repositories, with the real [ClaimLedger] and [AnchorRegistry] in between:
 * these tests are about what gets written, so the two parts stay real.
 */
private fun AccountService(
    accountRepository: AccountRepository,
    accountClaimRepository: AccountClaimRepository,
    accountAnchorRepository: AccountAnchorRepository,
    accountAuthMethodRepository: AccountAuthMethodRepository,
    accountRetractionRepository: AccountRetractionRepository,
    eventPublisher: ApplicationEventPublisher,
    changeLog: ChangeLog,
    personLookupKey: PersonLookupKey,
): AccountService {
    val ledger = ClaimLedger(accountClaimRepository, accountRetractionRepository, changeLog, clock = TEST_CLOCK)
    return AccountService(accountRepository, ledger, AnchorRegistry(accountAnchorRepository, ledger), accountAuthMethodRepository, eventPublisher, changeLog, personLookupKey, TEST_CLOCK)
}
