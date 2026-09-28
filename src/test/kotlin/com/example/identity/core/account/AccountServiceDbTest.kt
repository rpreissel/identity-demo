package com.example.identity.core.account

import com.example.identity.core.account.infrastructure.AccountAnchorRepository
import com.example.identity.core.account.infrastructure.ChangeLogRepository
import com.example.identity.core.account.infrastructure.ChangeType
import com.example.identity.core.orchestrator.api.v1.OrchestratorExceptionHandler
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.shouldBe
import org.aopalliance.intercept.MethodInterceptor
import org.hibernate.exception.ConstraintViolationException
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.aop.framework.Advised
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Real-DB counterpart to [AccountServiceTest]: that test pins the per-call decisions, this one pins
 * that the surrounding transaction commits or rolls back as one unit and that unique constraints hold
 * against the real H2 schema. A MockK-based test cannot show either.
 */
@SpringBootTest
@ActiveProfiles("test")
class AccountServiceDbTest(
    private val accountService: AccountService,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionManager: PlatformTransactionManager,
    private val anchorRepository: AccountAnchorRepository,
    private val changeLogRepository: ChangeLogRepository
) : BehaviorSpec({

    // Runs first in every `when`: beforeEach would only precede the `then` leaves, after the action.
    fun clearAccounts() {
        jdbcTemplate.update("DELETE FROM account.account")
    }

    given("account creation and claim adoption sharing the caller transaction") {
        `when`("a new account adopts a claim batch whose email another account holds") {
            clearAccounts()
            val holder = accountService.createUnidentifiedAccount()
            accountService.recordClaim(holder.accountId, Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)
            val result = runCatching {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    val subject = accountService.createUnidentifiedAccount()
                    accountService.recordClaims(subject.accountId, listOf(
                        Claim(AttributeType.PERSON_ID, "P000000555", ClaimSource.PERSON_DIRECTORY),
                        Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED)
                    ), provenAcr = AcrLevel.LOA2)
                }
            }

            then("a later claim conflict also removes the newly created account") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
                accountService.allAccountIds() shouldBe listOf(holder.accountId)
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 1
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 1
                accountService.anchorHolder(AttributeType.PERSON_ID, "P000000555").shouldBeNull()
            }
        }

        `when`("a later step fails after every claim was accepted") {
            clearAccounts()
            val result = runCatching {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    val subject = accountService.createUnidentifiedAccount()
                    accountService.recordClaims(subject.accountId, listOf(
                        Claim(AttributeType.PERSON_ID, "P000000555", ClaimSource.PERSON_DIRECTORY),
                        Claim(AttributeType.EMAIL, "new@example.com", ClaimSource.SELF_REPORTED)
                    ), provenAcr = AcrLevel.LOA2)
                    error("Later journey step failed")
                }
            }

            then("a failure after accepting every claim rolls back account, log, projection and anchors") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
                accountService.allAccountIds() shouldBe emptyList()
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 0
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 0
            }
        }
    }

    for ((type, value) in listOf(AttributeType.PERSON_ID to "P000000777", AttributeType.EMAIL to "shared@example.com")) {
        given("two concurrent new accounts claiming the same ${type.wireName}") {
            `when`("both transactions insert the anchor at the same time") {
                clearAccounts()
                val barrier = CyclicBarrier(2)
                val repositoryProxy = checkNotNull(anchorRepository as? Advised)
                val synchronizeInsert = MethodInterceptor { invocation ->
                    // Both transactions pass the real ownership queries before either inserts.
                    if (invocation.method.name == "save") barrier.await(10, TimeUnit.SECONDS)
                    invocation.proceed()
                }
                repositoryProxy.addAdvice(0, synchronizeInsert)
                val executor = Executors.newFixedThreadPool(2)
                val results = try {
                    val attempts = (1..2).map {
                        executor.submit<Result<Long>> {
                            runCatching {
                                checkNotNull(TransactionTemplate(transactionManager).execute {
                                    accountService.anchorHolder(type, value).shouldBeNull()
                                    val subject = accountService.createUnidentifiedAccount()
                                    accountService.recordClaim(subject.accountId, Claim(type, value, ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)
                                    subject.accountId
                                })
                            }
                        }
                    }
                    attempts.map { it.get(20, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                    check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Concurrent claim tasks did not terminate" }
                    repositoryProxy.removeAdvice(synchronizeInsert)
                }

                then("one commits and the unique-conflict loser leaves no account or claims behind") {
                    results.count { it.isSuccess } shouldBe 1
                    val winnerId = results.single { it.isSuccess }.getOrThrow()
                    val failure = checkNotNull(results.single { it.isFailure }.exceptionOrNull())
                    val violation = generateSequence(failure) { it.cause }
                        .filterIsInstance<ConstraintViolationException>().first()
                    OrchestratorExceptionHandler().handleConstraintViolation(violation).statusCode shouldBe HttpStatus.CONFLICT
                    accountService.allAccountIds() shouldBe listOf(winnerId)
                    accountService.anchorHolder(type, value) shouldBe winnerId
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 1
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 1
                    val winner = checkNotNull(accountService.findAccount(winnerId))
                    when (type) {
                        AttributeType.PERSON_ID -> winner.personId shouldBe value
                        AttributeType.EMAIL -> winner.email shouldBe value
                        else -> error("Unexpected test attribute")
                    }
                }
            }
        }
    }

    given("a claim batch whose second claim conflicts with another account's anchor") {
        `when`("the batch is recorded") {
            clearAccounts()
            val holder = accountService.createUnidentifiedAccount()
            accountService.recordClaim(holder.accountId, Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)

            val subject = accountService.createUnidentifiedAccount()

            val result = runCatching {
                accountService.recordClaims(
                    subject.accountId,
                    listOf(
                        Claim(AttributeType.PERSON_ID, "P000000555", ClaimSource.PERSON_DIRECTORY),
                        Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED)
                    ), provenAcr = AcrLevel.LOA2)
            }

            then("the whole batch rolls back - no partial log/projection/anchor survives from the first claim") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }

                // recordClaims is one @Transactional method: the EMAIL claim's failure must also undo the
                // PERSON_ID claim processed earlier in the same call.
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.claim WHERE account_id = ?", Int::class.java, subject.accountId
                ) shouldBe 0
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE account_id = ?", Int::class.java, subject.accountId
                ) shouldBe 0
                accountService.findAccount(subject.accountId)?.personId.shouldBeNull()
            }
        }
    }

    given("two existing accounts rebinding email concurrently") {
        `when`("both claim the same new email and commit at the same time") {
            clearAccounts()
            val ids = (1..2).map { index ->
                val account = accountService.createUnidentifiedAccount()
                accountService.recordClaim(account.accountId, Claim(AttributeType.EMAIL, "old$index@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)
                account.accountId
            }
            val commitBarrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            val results = try {
                ids.map { accountId ->
                    executor.submit<Result<Long>> {
                        runCatching {
                            checkNotNull(TransactionTemplate(transactionManager).execute {
                                accountService.recordClaim(accountId, Claim(AttributeType.EMAIL, "shared@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)
                                // Existing anchor updates are deferred: both callbacks finish before commit.
                                commitBarrier.await(10, TimeUnit.SECONDS)
                                accountId
                            })
                        }
                    }
                }.map { it.get(20, TimeUnit.SECONDS) }
            } finally {
                executor.shutdownNow()
                check(executor.awaitTermination(10, TimeUnit.SECONDS)) { "Concurrent rebind tasks did not terminate" }
            }

            then("a unique conflict at commit rolls back the losing projection and claim") {
                results.count { it.isSuccess } shouldBe 1
                val winner = results.single { it.isSuccess }.getOrThrow()
                val loser = ids.single { it != winner }
                val failure = checkNotNull(results.single { it.isFailure }.exceptionOrNull())
                val violation = generateSequence(failure) { it.cause }.filterIsInstance<ConstraintViolationException>().first()
                OrchestratorExceptionHandler().handleConstraintViolation(violation).statusCode shouldBe HttpStatus.CONFLICT
                accountService.anchorHolder(AttributeType.EMAIL, "shared@example.com") shouldBe winner
                accountService.findAccount(loser)?.email shouldBe "old${ids.indexOf(loser) + 1}@example.com"
                accountService.allAccountIds().sorted() shouldBe ids.sorted()
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim", Int::class.java) shouldBe 3
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.anchor", Int::class.java) shouldBe 2
            }
        }
    }

    given("two accounts, the second trying to claim a person_id the first already holds") {
        `when`("the second account records the person_id") {
            clearAccounts()
            val first = accountService.createUnidentifiedAccount()
            accountService.recordClaim(first.accountId, Claim(AttributeType.PERSON_ID, "P000000777", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)

            val second = accountService.createUnidentifiedAccount()
            val result = runCatching {
                accountService.recordClaim(second.accountId, Claim(AttributeType.PERSON_ID, "P000000777", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)
            }

            then("the real unique index rejects it - exactly one owner survives") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }

                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE attribute_type = 'person_id' AND normalized_value = 'P000000777'",
                    Int::class.java
                ) shouldBe 1
                accountService.findAccount(second.accountId)?.personId.shouldBeNull()
            }
        }
    }

    given("an account's email anchor being rebound to a new value") {
        `when`("the new email is recorded") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(account.accountId, Claim(AttributeType.EMAIL, "old@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)
            accountService.recordClaim(account.accountId, Claim(AttributeType.EMAIL, "new@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)

            then("the rebind commits atomically under real unique constraints") {
                accountService.findAccountByEmail("old@example.com").shouldBeNull()
                accountService.findAccountByEmail("new@example.com")?.accountId shouldBe account.accountId
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE account_id = ? AND attribute_type = 'email'",
                    Int::class.java, account.accountId
                ) shouldBe 1
            }
        }
    }

    // ADR-12: a retraction cancels a claim without touching the log, and established-claims
    // readers see assertions MINUS retractions. Needs the real schema - the `not exists`
    // subtraction is SQL.
    given("a claim that was retracted") {
        `when`("the retraction is written") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaims(account.accountId, listOf(
                Claim(AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY),
                Claim(AttributeType.GIVEN_NAMES, "Max", ClaimSource.PERSON_DIRECTORY),
                Claim(AttributeType.BIRTH_DATE, "1985-06-15", ClaimSource.PERSON_DIRECTORY)
            ), provenAcr = AcrLevel.LOA2)
            fun establishedValues() = accountService.establishedClaimValues(
                account.accountId, setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)
            )
            val beforeRetraction = establishedValues()

            jdbcTemplate.update(
                """INSERT INTO account.retraction (account_id, attribute_type, normalized_value, trust_anchor, retracted_at)
                   VALUES (?, 'family_name', 'muster', 'OPERATOR', CURRENT_TIMESTAMP)""",
                account.accountId
            )

            then("it stops counting although its log row stays") {
                beforeRetraction shouldBe mapOf(
                    AttributeType.FAMILY_NAME to "Muster",
                    AttributeType.GIVEN_NAMES to "Max",
                    AttributeType.BIRTH_DATE to "1985-06-15"
                )
                establishedValues() shouldBe mapOf(
                    AttributeType.GIVEN_NAMES to "Max",
                    AttributeType.BIRTH_DATE to "1985-06-15"
                )
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.claim WHERE account_id = ? AND attribute_type = 'family_name'",
                    Int::class.java, account.accountId
                ) shouldBe 1
            }
        }
    }

    given("a singleton method replaced by a new instance") {
        fun enroll(accountId: Long, method: String, claim: Claim, ref: String) {
            val instance = java.util.UUID.randomUUID()
            // Same order as the enrollment path: the new instance's claims first, then the instance.
            accountService.recordClaims(accountId, listOf(claim), provenAcr = AcrLevel.LOA1, authMethodId = instance)
            accountService.addAuthenticationMethod(accountId, method, EnrollmentRef("t", ref), "loa1", emptyMap(), instanceId = instance)
        }
        val smsTool = ClaimSource.of(ToolId("enroll-sms"))
        val passwordTool = ClaimSource.of(ToolId("enroll-password"))

        `when`("a second sms method with a new phone number is enrolled") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            enroll(account.accountId, "sms", Claim(AttributeType.PHONE_NUMBER, "+491700000001", smsTool), "s-1")
            enroll(account.accountId, "sms", Claim(AttributeType.PHONE_NUMBER, "+491700000002", smsTool), "s-2")

            then("the old phone number stops counting, the new one counts") {
                accountService.establishedClaimValues(account.accountId, setOf(AttributeType.PHONE_NUMBER)) shouldBe
                    mapOf(AttributeType.PHONE_NUMBER to "+491700000002")
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.retraction WHERE account_id = ? AND attribute_type = 'phone_number'",
                    Int::class.java, account.accountId
                ) shouldBe 1
            }
        }

        `when`("a second password method asserting the same value is enrolled") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            val exists = Claim(AttributeType.PASSWORD_EXISTS, com.example.identity.contract.tool_api.claims.PASSWORD_EXISTS_MARKER, passwordTool)
            enroll(account.accountId, "password", exists, "p-1")
            enroll(account.accountId, "password", exists, "p-2")

            then("a value the replacement asserts itself stays - a new password still means 'has a password'") {
                accountService.findAccount(account.accountId)!!.establishedClaims.keys.contains(AttributeType.PASSWORD_EXISTS) shouldBe true
            }
        }
    }

    given("the Personenverzeichnis moves a Versicherungsnummer to another person before the old holder's own change arrived") {
        `when`("the receiver's directory change is applied") {
            clearAccounts()
            fun bound(personId: String, versnr: String): Long {
                val account = accountService.createUnidentifiedAccount()
                accountService.recordClaims(
                    account.accountId,
                    listOf(
                        Claim(AttributeType.PERSON_ID, personId, ClaimSource.PERSON_DIRECTORY),
                        Claim(AttributeType.INSURANCE_NUMBER, versnr, ClaimSource.PERSON_DIRECTORY)
                    ),
                    provenAcr = AcrLevel.LOA2
                )
                return account.accountId
            }
            fun insuranceNumberOf(accountId: Long): String? = jdbcTemplate.queryForList(
                "SELECT normalized_value FROM account.anchor WHERE account_id = ? AND attribute_type = 'insurance_number'",
                String::class.java, accountId
            ).firstOrNull()
            val stale = bound("P000000001", "10000001")
            val receiver = bound("P000000002", "10000002")

            accountService.applyDirectoryChange(
                com.example.identity.contract.tool_api.directory.PersonChanged("P000000002", setOf(AttributeType.INSURANCE_NUMBER), kvnr = null, insuranceNumber = "10000001")
            )

            then("it is released from the stale holder instead of failing on the anchor conflict forever") {
                insuranceNumberOf(receiver) shouldBe "10000001"
                insuranceNumberOf(stale).shouldBeNull()
                jdbcTemplate.queryForObject(
                    "SELECT trust_anchor FROM account.retraction WHERE account_id = ? AND attribute_type = 'insurance_number'",
                    String::class.java, stale
                ) shouldBe "PERSON_DIRECTORY"
            }
        }
    }

    given("an identity anchor and a withdrawal in the holder's name") {
        `when`("the holder retracts the person_id") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaims(
                account.accountId,
                listOf(Claim(AttributeType.PERSON_ID, "P000000042", ClaimSource.PERSON_DIRECTORY)),
                provenAcr = AcrLevel.LOA2
            )

            val result = runCatching {
                accountService.retractAttribute(account.accountId, AttributeType.PERSON_ID, RetractionAnchor.ACCOUNT_HOLDER)
            }

            then("the account module itself refuses it, whatever the caller checked") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
                accountService.findAccount(account.accountId)!!.personId shouldBe "P000000042"
            }
        }
    }

    given("an eid restricted_id anchor being replaced by a new card (ADR-19)") {
        `when`("the new card's restricted_id is recorded") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                account.accountId,
                Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", ClaimSource.of(ToolId("ident-eid"))),
                provenAcr = AcrLevel.LOA2
            )
            accountService.recordClaim(
                account.accountId,
                Claim(AttributeType.EID_RESTRICTED_ID, "T0909090Z9X8Y7W6V5U4T3S2R1", ClaimSource.of(ToolId("ident-eid"))),
                provenAcr = AcrLevel.LOA2
            )

            then("the replace commits in place, like EMAIL - the account keeps exactly one") {
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE account_id = ? AND attribute_type = 'restricted_id'",
                    Int::class.java, account.accountId
                ) shouldBe 1
                jdbcTemplate.queryForObject(
                    "SELECT normalized_value FROM account.anchor WHERE account_id = ? AND attribute_type = 'restricted_id'",
                    String::class.java, account.accountId
                ) shouldBe "T0909090Z9X8Y7W6V5U4T3S2R1"
            }
        }
    }

    given("an eid restricted_id another account already holds") {
        `when`("a second account records the same restricted_id") {
            clearAccounts()
            val first = accountService.createUnidentifiedAccount()
            val second = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                first.accountId,
                Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", ClaimSource.of(ToolId("ident-eid"))),
                provenAcr = AcrLevel.LOA2
            )

            val result = runCatching {
                accountService.recordClaim(
                    second.accountId,
                    Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", ClaimSource.of(ToolId("ident-eid"))),
                    provenAcr = AcrLevel.LOA2
                )
            }

            then("the cross-account write refuses - a card pseudonym is never re-pointed to a second account") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
            }
        }
    }

    given("a method instance that asserted a module-owned value and an account-owned one") {
        `when`("the instance's claims are retracted") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            val instanceId = java.util.UUID.randomUUID()
            accountService.recordClaims(
                account.accountId,
                listOf(
                    Claim(AttributeType.PHONE_NUMBER, "+491701234567", ClaimSource.of(ToolId("enroll-sms"))),
                    Claim(AttributeType.EMAIL, "max@example.com", ClaimSource.of(ToolId("enroll-sms")))
                ),
                authMethodId = instanceId,
                provenAcr = AcrLevel.LOA2
            )

            val retracted = accountService.retractClaimsOf(
                account.accountId, instanceId.toString(), RetractionAnchor.ACCOUNT_MANAGEMENT
            )

            then("revoking it retracts only what the module owned") {
                retracted shouldBe 1

                // The phone number was the module's; the email is the account's own identity anchor and
                // survives - otherwise removing the sms method would take password login with it.
                jdbcTemplate.queryForObject(
                    "SELECT attribute_type FROM account.retraction WHERE account_id = ?",
                    String::class.java, account.accountId
                ) shouldBe "phone_number"
                accountService.findAccount(account.accountId)?.email shouldBe "max@example.com"
            }
        }
    }

    // ADR-5's line applied to anchors: a write is priced by what the session actually proved,
    // not by what the asserting tool declares for itself.
    given("an anchor write below its declared floor") {
        `when`("a person_id is established from a loa1 session") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()

            val result = runCatching {
                accountService.recordClaim(
                    account.accountId,
                    Claim(AttributeType.PERSON_ID, "P000004711", ClaimSource.PERSON_DIRECTORY, AcrLevel.LOA2),
                    provenAcr = AcrLevel.LOA1
                )
            }

            then("establishing a person_id at loa1 is refused, and nothing is anchored") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
                accountService.findAccount(account.accountId)?.personId.shouldBeNull()
            }
        }

        `when`("an email established at loa1 is replaced from a loa1 session") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                account.accountId,
                Claim(AttributeType.EMAIL, "first@example.com", ClaimSource.SELF_REPORTED),
                provenAcr = AcrLevel.LOA1
            )

            val result = runCatching {
                accountService.recordClaim(
                    account.accountId,
                    Claim(AttributeType.EMAIL, "second@example.com", ClaimSource.SELF_REPORTED),
                    provenAcr = AcrLevel.LOA1
                )
            }

            then("replacing an email costs loa2 even though establishing it cost loa1") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
                accountService.findAccount(account.accountId)?.email shouldBe "first@example.com"
            }
        }
    }

    given("an anchor that was written") {
        `when`("a loa2 email claim is recorded from a loa1 session") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                account.accountId,
                // The claim declares loa2; the session only ever proved loa1, and that is what counts.
                Claim(AttributeType.EMAIL, "capped@example.com", ClaimSource.SELF_REPORTED, AcrLevel.LOA2),
                provenAcr = AcrLevel.LOA1
            )

            then("it remembers the level the session actually proved, not the claim's own") {
                jdbcTemplate.queryForObject(
                    "SELECT established_acr FROM account.anchor WHERE account_id = ? AND attribute_type = 'email'",
                    String::class.java, account.accountId
                ) shouldBe "loa1"
            }
        }
    }

    // ADR-19 / ADR-12-Nachtrag: the claim log is a change log, not a run log. Re-attesting an
    // unchanged card costs nothing.
    given("a claim log that already established a tool's values") {
        `when`("the same card is attested again") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            val eid = ClaimSource.of(ToolId("ident-eid"))
            val card = listOf(
                Claim(AttributeType.FAMILY_NAME, "Mustermann", eid, AcrLevel.LOA3),
                Claim(AttributeType.GIVEN_NAMES, "Max", eid, AcrLevel.LOA3),
                Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", eid, AcrLevel.LOA3)
            )
            accountService.recordClaims(account.accountId, card, provenAcr = AcrLevel.LOA2)
            accountService.recordClaims(account.accountId, card, provenAcr = AcrLevel.LOA2)

            then("re-attesting the same card logs nothing new (change log, not run log)") {
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.claim WHERE account_id = ?", Int::class.java, account.accountId
                ) shouldBe 3
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE account_id = ?", Int::class.java, account.accountId
                ) shouldBe 1
            }
        }
    }

    // ADR-20: the provisional account yields to the account the correlation step resolves. Needs the
    // real schema: `ux_anchor_value` is global, so the yielding account's anchors must be gone before
    // the same values are written on the absorbing one.
    given("a provisional account whose attestation resolves to another account") {
        `when`("it is absorbed into an identified account") {
            clearAccounts()
            val eid = ClaimSource.of(ToolId("ident-eid"))
            val provisional = accountService.createUnidentifiedAccount()
            accountService.recordClaims(provisional.accountId, listOf(
                Claim(AttributeType.FAMILY_NAME, "Muster", eid, AcrLevel.LOA3),
                Claim(AttributeType.GIVEN_NAMES, "Max", eid, AcrLevel.LOA3),
                Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", eid, AcrLevel.LOA3)
            ), provenAcr = AcrLevel.LOA2)
            accountService.addIdentification(provisional.accountId, "eid", "loa3", role = "IDENTIFICATION", report = mapOf("provider" to "eid-mock-service"))
            val target = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                target.accountId, Claim(AttributeType.PERSON_ID, "P000000001", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2
            )

            accountService.absorbProvisionalAccount(provisional.accountId, target.accountId)

            then("it yields: anchors and claims move, the identification proof is carried over, the account is gone") {
                accountService.findAccount(provisional.accountId).shouldBeNull()
                accountService.findAccount(target.accountId)!!.personId shouldBe "P000000001"
                anchorRepository.findByAccountIdAndAttributeType(target.accountId, AttributeType.EID_RESTRICTED_ID)!!.value shouldBe
                    "T0103005K1D5S0V8T9W6UM2RTX"
                accountService.establishedClaimValues(target.accountId, setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES)) shouldBe
                    mapOf(AttributeType.FAMILY_NAME to "Muster", AttributeType.GIVEN_NAMES to "Max")
                // The proof of identity is the absorbing account's now (ADR-39): carried over, naming its origin.
                val carried = changeLogRepository.findByAccountIdAndChangeTypeOrderByOccurredAt(target.accountId, ChangeType.IDENTIFIED)
                    .single { it.subject == "eid" }.details!!
                carried["carriedFromAccountId"].toString() shouldBe provisional.accountId.toString()
                carried["provider"] shouldBe "eid-mock-service"
                carried["role"] shouldBe "IDENTIFICATION"
            }
        }

        `when`("it is absorbed into an account without that anchor") {
            clearAccounts()
            val eid = ClaimSource.of(ToolId("ident-eid"))
            val provisional = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                provisional.accountId, Claim(AttributeType.EMAIL, "max@example.com", eid), provenAcr = AcrLevel.LOA2
            )
            val target = accountService.createUnidentifiedAccount()

            accountService.absorbProvisionalAccount(provisional.accountId, target.accountId)

            then("an anchor the absorbing account already holds with the same value is a no-op, not a conflict") {
                accountService.findAccount(target.accountId)!!.email shouldBe "max@example.com"
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE attribute_type = 'email' AND normalized_value = 'max@example.com'",
                    Int::class.java
                ) shouldBe 1
            }
        }
    }

    // The mirror image ("Enrollment zuerst"): the durable account is in hand, and resolution finds the
    // placeholder an abandoned eID run left behind. Same port, arguments swapped; the provisional
    // account yields either way.
    given("a provisional leftover that an already-enrolled account identifies into") {
        `when`("the leftover is absorbed into the enrolled account") {
            clearAccounts()
            val eid = ClaimSource.of(ToolId("ident-eid"))
            val leftover = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                leftover.accountId,
                Claim(AttributeType.EID_RESTRICTED_ID, "T0304223A9B1N7K5D2PN1S44QE", eid, AcrLevel.LOA3),
                provenAcr = AcrLevel.LOA2
            )
            val enrolled = accountService.createUnidentifiedAccount()
            accountService.addAuthenticationMethod(
                enrolled.accountId, "password", EnrollmentRef("auth_password", "e-3"),
                enrolledUnderAcr = "loa1", details = emptyMap()
            )

            accountService.absorbProvisionalAccount(leftover.accountId, enrolled.accountId)

            then("the leftover is absorbed into the account that holds the credentials") {
                accountService.findAccount(leftover.accountId).shouldBeNull()
                anchorRepository.findByAccountIdAndAttributeType(enrolled.accountId, AttributeType.EID_RESTRICTED_ID)!!.value shouldBe
                    "T0304223A9B1N7K5D2PN1S44QE"
                accountService.findAccount(enrolled.accountId)!!.activeAuthenticationMethods.size shouldBe 1
            }
        }
    }

    given("an account that is not provisional") {
        `when`("it is to be absorbed into another account") {
            clearAccounts()
            val notProvisional = accountService.createUnidentifiedAccount()
            accountService.addAuthenticationMethod(
                notProvisional.accountId, "password", EnrollmentRef("auth_password", "e-1"),
                enrolledUnderAcr = "loa1", details = emptyMap()
            )
            val target = accountService.createUnidentifiedAccount()
            val isProvisional = accountService.findAccount(notProvisional.accountId)!!.isProvisional

            val result = runCatching {
                accountService.absorbProvisionalAccount(notProvisional.accountId, target.accountId)
            }

            then("it never yields - a credential was enrolled on it, so this would be an account merge") {
                isProvisional shouldBe false
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
                accountService.findAccount(notProvisional.accountId) shouldNotBe null
            }
        }

        `when`("its only credential is deactivated") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            val profile = accountService.addAuthenticationMethod(
                account.accountId, "password", EnrollmentRef("auth_password", "e-2"),
                enrolledUnderAcr = "loa1", details = emptyMap()
            )
            accountService.deactivateAuthenticationMethod(account.accountId, profile.authenticationMethods.first().id)

            then("a deactivated credential still counts - its claims\' provenance points at this account") {
                val reread = accountService.findAccount(account.accountId)!!
                reread.activeAuthenticationMethods.shouldBeEmpty()
                reread.isProvisional shouldBe false
            }
        }
    }

    given("an anchor that a replacement claim re-points") {
        `when`("a new email replaces the old one") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaim(
                account.accountId, Claim(AttributeType.EMAIL, "old@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA1
            )
            accountService.recordClaim(
                account.accountId, Claim(AttributeType.EMAIL, "new@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2
            )

            then("the old value is retracted so the log agrees with the anchor (ADR-19)") {
                accountService.establishedClaimValues(account.accountId, setOf(AttributeType.EMAIL)) shouldBe
                    mapOf(AttributeType.EMAIL to "new@example.com")
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.retraction WHERE account_id = ?", Int::class.java, account.accountId
                ) shouldBe 1
            }
        }

        `when`("the email goes from a to b and back to a") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            for (value in listOf("a@example.com", "b@example.com", "a@example.com")) {
                accountService.recordClaim(
                    account.accountId, Claim(AttributeType.EMAIL, value, ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2
                )
            }

            then("a re-proven value counts again - the cycle a -> b -> a ends established on a") {
                accountService.establishedClaimValues(account.accountId, setOf(AttributeType.EMAIL)) shouldBe
                    mapOf(AttributeType.EMAIL to "a@example.com")
                accountService.findAccountByEmail("a@example.com")?.accountId shouldBe account.accountId
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.claim WHERE account_id = ? AND attribute_type = 'email'",
                    Int::class.java, account.accountId
                ) shouldBe 3
            }
        }
    }

    given("a card anchor replaced by a value that differs only in case") {
        `when`("the upper-case value is recorded") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            val source = ClaimSource.of(com.example.identity.contract.tool_api.ToolId("ident-eid"))
            accountService.recordClaim(account.accountId, Claim(AttributeType.EID_RESTRICTED_ID, "AbC123", source), provenAcr = AcrLevel.LOA3)
            accountService.recordClaim(account.accountId, Claim(AttributeType.EID_RESTRICTED_ID, "ABC123", source), provenAcr = AcrLevel.LOA3)

            then("the new claim stands - the replacement does not void what it just set") {
                accountService.establishedClaimValues(account.accountId, setOf(AttributeType.EID_RESTRICTED_ID))[AttributeType.EID_RESTRICTED_ID] shouldBe "ABC123"
            }
        }
    }

    given("a claim a tool reports at loa2, established from a session that only proved loa1 (ADR-5)") {
        `when`("the claim is recorded") {
            clearAccounts()
            val account = accountService.createUnidentifiedAccount()
            accountService.recordClaims(
                account.accountId,
                listOf(Claim(AttributeType.PHONE_NUMBER, "+491701234567", ClaimSource.of(com.example.identity.contract.tool_api.ToolId("enroll-sms")), AcrLevel.LOA2)),
                provenAcr = AcrLevel.LOA1
            )

            then("the claim log records loa1 - what was proven, not the tool's ceiling") {
                jdbcTemplate.queryForObject(
                    "SELECT established_acr FROM account.claim WHERE account_id = ?", String::class.java, account.accountId
                ) shouldBe "loa1"
            }
        }
    }
})
