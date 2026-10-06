package com.example.identity.core.account.application

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.RetractionSource
import com.example.identity.core.account.infrastructure.AccountClaim
import com.example.identity.core.account.infrastructure.AccountClaimRepository
import com.example.identity.core.account.infrastructure.AccountRetraction
import com.example.identity.core.account.infrastructure.AccountRetractionRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import java.time.Duration

/**
 * ADR-52, the ledger's part: one batch per retention rule, so a data key can expire as a whole,
 * and a batch nobody asserts any more loses its key.
 */
class ClaimLedgerTest : BehaviorSpec({

    val account = AccountId(7L)
    val eid = ClaimSource("ident-eid")

    class Fixture(retention: Map<String, Duration>) {
        val keys = ClaimCryptoFixture()
        val claimRepository = mockk<AccountClaimRepository>()
        val retractionRepository = mockk<AccountRetractionRepository>()
        val saved = mutableListOf<AccountClaim>()
        val retractions = mutableListOf<AccountRetraction>()
        val ledger = ClaimLedger(
            claimRepository, retractionRepository, mockk(relaxed = true), keys.crypto,
            ClaimRetentionPolicy(ClaimRetentionProperties(retention)), TEST_CLOCK
        )

        init {
            keys.account(account)
            // The log answers from what was saved, minus what was retracted - the SQL subtraction in Kotlin.
            every { claimRepository.findEstablished(account) } answers {
                saved.filter { claim ->
                    retractions.none {
                        it.attributeType == claim.attributeType && it.valueDigest == claim.valueDigest &&
                            (it.claimBatchId == null || it.claimBatchId == claim.claimBatchId)
                    }
                }
            }
            every { claimRepository.findByAccountIdAndAttributeTypeAndValueDigest(account, any(), any()) } answers {
                saved.filter { it.attributeType == secondArg<AttributeType>() && it.valueDigest == thirdArg<String?>() }
            }
            every { claimRepository.save(any<AccountClaim>()) } answers { firstArg<AccountClaim>().also(saved::add) }
            every { retractionRepository.save(any<AccountRetraction>()) } answers { firstArg<AccountRetraction>().also(retractions::add) }
        }
    }

    given("names expire after a year, the address does not") {
        val fixture = Fixture(mapOf("family_name" to Duration.ofDays(365), "given_names" to Duration.ofDays(365)))

        `when`("an eID run records names and address in one call") {
            fixture.ledger.append(account, listOf(
                Claim(AttributeType.FAMILY_NAME, "Muster", eid, AcrLevel.LOA3),
                Claim(AttributeType.GIVEN_NAMES, "Max", eid, AcrLevel.LOA3),
                Claim(AttributeType.LOCALITY, "Musterstadt", eid, AcrLevel.LOA3),
            ), provenAcr = AcrLevel.LOA3, authMethodId = null)

            then("the names share one batch that expires, the address has its own that does not") {
                val byType = fixture.saved.associateBy { it.attributeType }
                byType.getValue(AttributeType.FAMILY_NAME).claimBatchId shouldBe byType.getValue(AttributeType.GIVEN_NAMES).claimBatchId
                byType.getValue(AttributeType.LOCALITY).claimBatchId shouldBe byType.getValue(AttributeType.LOCALITY).claimBatchId
                fixture.keys.batchKeys shouldHaveSize 2
                val names = fixture.keys.batchKeys.single { it.claimBatchId == byType.getValue(AttributeType.FAMILY_NAME).claimBatchId }
                names.expiresAt shouldBe TEST_NOW + Duration.ofDays(365)
                fixture.keys.batchKeys.single { it.claimBatchId == byType.getValue(AttributeType.LOCALITY).claimBatchId }.expiresAt.shouldBeNull()
            }

            then("the values read back through the ledger") {
                fixture.ledger.establishedValues(account, setOf(AttributeType.FAMILY_NAME, AttributeType.LOCALITY)) shouldBe
                    mapOf(AttributeType.FAMILY_NAME to "Muster", AttributeType.LOCALITY to "Musterstadt")
            }
        }

        `when`("the same names are recorded again") {
            fixture.ledger.append(account, listOf(Claim(AttributeType.FAMILY_NAME, "muster ", eid, AcrLevel.LOA3)), AcrLevel.LOA3, null)

            then("no row and no batch is added - the digest recognizes the spelling") {
                fixture.saved shouldHaveSize 3
                fixture.keys.batchKeys shouldHaveSize 2
            }
        }
    }

    given("a batch with a single claim") {
        val fixture = Fixture(emptyMap())
        fixture.ledger.append(account, listOf(Claim(AttributeType.LOCALITY, "Musterstadt", eid, AcrLevel.LOA3)), AcrLevel.LOA3, null)

        `when`("that claim is retracted") {
            fixture.ledger.retractEstablished(account, AttributeType.LOCALITY, RetractionSource.OPERATOR, "test", TEST_NOW)

            then("its batch key is deleted and the value is unreadable, the row stays") {
                fixture.keys.batchKeys.shouldBeEmpty()
                fixture.saved shouldHaveSize 1
                fixture.keys.valueOf(fixture.saved.single()).shouldBeNull()
                fixture.retractions.single().retractionSource shouldBe RetractionSource.OPERATOR
            }
        }
    }

    given("a batch with two claims") {
        val fixture = Fixture(emptyMap())
        fixture.ledger.append(account, listOf(
            Claim(AttributeType.LOCALITY, "Musterstadt", eid, AcrLevel.LOA3),
            Claim(AttributeType.POSTAL_CODE, "12345", eid, AcrLevel.LOA3),
        ), AcrLevel.LOA3, null)

        `when`("one of them is retracted") {
            fixture.ledger.retractEstablished(account, AttributeType.LOCALITY, RetractionSource.OPERATOR, "test", TEST_NOW)

            then("the batch key stays for the other one") {
                fixture.keys.batchKeys shouldHaveSize 1
                fixture.ledger.establishedValues(account, setOf(AttributeType.LOCALITY, AttributeType.POSTAL_CODE)) shouldBe
                    mapOf(AttributeType.POSTAL_CODE to "12345")
            }
        }

    }

    given("the same name in an old batch and in a younger one from another source") {
        val fixture = Fixture(emptyMap())
        fixture.ledger.append(account, listOf(Claim(AttributeType.FAMILY_NAME, "Muster", eid, AcrLevel.LOA3)), AcrLevel.LOA3, null)
        val old = fixture.keys.batchKeys.single()
        fixture.ledger.append(account, listOf(Claim(AttributeType.FAMILY_NAME, "Muster", ClaimSource("ident-nect"), AcrLevel.LOA3)), AcrLevel.LOA3, null)

        `when`("the old batch expires") {
            fixture.ledger.expire(old, TEST_NOW + Duration.ofDays(400))

            then("the younger batch keeps its value and its key - the retraction names the batch") {
                fixture.retractions.single().claimBatchId shouldBe old.claimBatchId
                fixture.keys.batchKeys.map { it.claimBatchId } shouldBe listOf(fixture.saved.last().claimBatchId)
                fixture.ledger.establishedValues(account, setOf(AttributeType.FAMILY_NAME)) shouldBe mapOf(AttributeType.FAMILY_NAME to "Muster")
            }
        }
    }

    given("a batch with two claims whose retention runs out") {
        val fixture = Fixture(emptyMap())
        fixture.ledger.append(account, listOf(
            Claim(AttributeType.LOCALITY, "Musterstadt", eid, AcrLevel.LOA3),
            Claim(AttributeType.POSTAL_CODE, "12345", eid, AcrLevel.LOA3),
        ), AcrLevel.LOA3, null)

        `when`("the batch is expired") {
            fixture.ledger.expire(fixture.keys.batchKeys.single(), TEST_NOW + Duration.ofDays(400))

            then("both claims are withdrawn by the policy and the key is gone") {
                fixture.retractions.map { it.retractionSource }.toSet() shouldBe setOf(RetractionSource.RETENTION_POLICY)
                fixture.retractions shouldHaveSize 2
                fixture.keys.batchKeys.shouldBeEmpty()
                fixture.ledger.establishedValues(account, setOf(AttributeType.LOCALITY, AttributeType.POSTAL_CODE)) shouldBe emptyMap()
            }
        }
    }
})
