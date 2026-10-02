package com.example.identity.core.account

import com.example.identity.core.orchestrator.SharedSpringContext
import com.example.identity.tools.auth_password.PASSWORD_EXISTS
import com.example.identity.tools.auth_sms.PHONE_NUMBER
import com.example.identity.tools.ident_eid.EID_RESTRICTED_ID
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
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
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.aopalliance.intercept.MethodInterceptor
import org.hibernate.exception.ConstraintViolationException
import org.springframework.aop.framework.Advised
import com.example.identity.contract.tool_api.EnrollmentRef
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.http.HttpStatus
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
class AccountServiceDbTest(
    private val accountService: AccountService,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionManager: PlatformTransactionManager,
    private val anchorRepository: AccountAnchorRepository,
    private val changeLogRepository: ChangeLogRepository
) : SharedSpringContext({

    // Runs first in every `when`: beforeEach would only precede the `then` leaves, after the action.
    fun clearAccounts() {
        jdbcTemplate.update("DELETE FROM account.account")
    }

    given("account creation and claim adoption sharing the caller transaction") {
        `when`("a new account adopts a claim batch whose email another account holds") {
            clearAccounts()
            val holder = accountService.createAccountInSetup()
            accountService.recordClaim(holder.accountId, Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)
            val result = runCatching {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    val subject = accountService.createAccountInSetup()
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
                    val subject = accountService.createAccountInSetup()
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
                        executor.submit<Result<AccountId>> {
                            runCatching {
                                checkNotNull(TransactionTemplate(transactionManager).execute {
                                    accountService.anchorHolder(type, value).shouldBeNull()
                                    val subject = accountService.createAccountInSetup()
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
                        AttributeType.PERSON_ID -> winner.personId shouldBe PartnerNumber(value)
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
            val holder = accountService.createAccountInSetup()
            accountService.recordClaim(holder.accountId, Claim(AttributeType.EMAIL, "taken@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)

            val subject = accountService.createAccountInSetup()

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
                    "SELECT COUNT(*) FROM account.claim WHERE account_id = ?", Int::class.java, subject.accountId.value
                ) shouldBe 0
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE account_id = ?", Int::class.java, subject.accountId.value
                ) shouldBe 0
                accountService.findAccount(subject.accountId)?.personId.shouldBeNull()
            }
        }
    }

    given("two existing accounts rebinding email concurrently") {
        `when`("both claim the same new email and commit at the same time") {
            clearAccounts()
            val ids = (1..2).map { index ->
                val account = accountService.createAccountInSetup()
                accountService.recordClaim(account.accountId, Claim(AttributeType.EMAIL, "old$index@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2)
                account.accountId
            }
            val commitBarrier = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            val results = try {
                ids.map { accountId ->
                    executor.submit<Result<AccountId>> {
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
            val first = accountService.createAccountInSetup()
            accountService.recordClaim(first.accountId, Claim(AttributeType.PERSON_ID, "P000000777", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)

            val second = accountService.createAccountInSetup()
            val result = runCatching {
                accountService.recordClaim(second.accountId, Claim(AttributeType.PERSON_ID, "P000000777", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2)
            }

            then("the conflict check refuses it before any write - exactly one owner survives") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }

                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE attribute_type = 'person_id' AND normalized_value = 'P000000777'",
                    Int::class.java
                ) shouldBe 1
                accountService.findAccount(second.accountId)?.personId.shouldBeNull()
            }
        }
    }

    // ADR-12: a retraction cancels a claim without touching the log, and established-claims
    // readers see assertions MINUS retractions. Needs the real schema - the `not exists`
    // subtraction is SQL.
    given("a claim that was retracted") {
        `when`("the retraction is written") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
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
                """INSERT INTO account.retraction (account_id, attribute_type, normalized_value, claim_source, retracted_at)
                   VALUES (?, 'family_name', 'muster', 'OPERATOR', CURRENT_TIMESTAMP)""",
                account.accountId.value
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
                    Int::class.java, account.accountId.value
                ) shouldBe 1
            }
        }
    }

    given("a singleton method replaced by a new instance") {
        fun enroll(accountId: AccountId, method: String, claim: Claim, ref: String) {
            val instance = java.util.UUID.randomUUID()
            // Same order as the enrollment path: the new instance's claims first, then the instance.
            accountService.recordClaims(accountId, listOf(claim), provenAcr = AcrLevel.LOA1, authMethodId = instance)
            accountService.addAuthenticationMethod(accountId, method, EnrollmentRef("t", ref), "loa1", instanceId = instance)
        }
        val smsTool = ClaimSource("enroll-sms")
        val passwordTool = ClaimSource("enroll-password")

        `when`("a second sms method with a new phone number is enrolled") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
            enroll(account.accountId, "sms", Claim(PHONE_NUMBER, "+491700000001", smsTool), "s-1")
            enroll(account.accountId, "sms", Claim(PHONE_NUMBER, "+491700000002", smsTool), "s-2")

            then("the old phone number stops counting, the new one counts") {
                accountService.establishedClaimValues(account.accountId, setOf(PHONE_NUMBER)) shouldBe
                    mapOf(PHONE_NUMBER to "+491700000002")
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.retraction WHERE account_id = ? AND attribute_type = 'phone_number'",
                    Int::class.java, account.accountId.value
                ) shouldBe 1
            }
        }

        `when`("a second password method asserting the same value is enrolled") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
            val exists = Claim(PASSWORD_EXISTS, com.example.identity.tools.auth_password.PASSWORD_EXISTS_MARKER, passwordTool)
            enroll(account.accountId, "password", exists, "p-1")
            enroll(account.accountId, "password", exists, "p-2")

            then("a value the replacement asserts itself stays - a new password still means 'has a password'") {
                accountService.findAccount(account.accountId)!!.establishedClaims.keys.contains(PASSWORD_EXISTS) shouldBe true
            }
        }
    }

    given("the Personenverzeichnis moves a Versicherungsnummer to another person before the old holder's own change arrived") {
        `when`("the receiver's directory change is applied") {
            clearAccounts()
            fun bound(personId: PartnerNumber, versnr: String): AccountId {
                val account = accountService.createAccountInSetup()
                accountService.recordClaims(
                    account.accountId,
                    listOf(
                        Claim(AttributeType.PERSON_ID, personId.value, ClaimSource.PERSON_DIRECTORY),
                        Claim(AttributeType.MEMBER_NUMBER, versnr, ClaimSource.PERSON_DIRECTORY)
                    ),
                    provenAcr = AcrLevel.LOA2
                )
                return account.accountId
            }
            fun memberNumberOf(accountId: AccountId): String? = jdbcTemplate.queryForList(
                "SELECT normalized_value FROM account.anchor WHERE account_id = ? AND attribute_type = 'member_number'",
                String::class.java, accountId.value
            ).firstOrNull()
            val stale = bound(PartnerNumber("P000000001"), "10000001")
            val receiver = bound(PartnerNumber("P000000002"), "10000002")

            accountService.applyDirectoryChange(
                com.example.identity.contract.tool_api.directory.PersonChanged(PartnerNumber("P000000002"), setOf(AttributeType.MEMBER_NUMBER), kvnr = null, memberNumber = "10000001")
            )

            then("it is released from the stale holder instead of failing on the anchor conflict forever") {
                memberNumberOf(receiver) shouldBe "10000001"
                memberNumberOf(stale).shouldBeNull()
                jdbcTemplate.queryForObject(
                    "SELECT claim_source FROM account.retraction WHERE account_id = ? AND attribute_type = 'member_number'",
                    String::class.java, stale.value
                ) shouldBe "PERSON_DIRECTORY"
            }
        }
    }

    given("an eid restricted_id anchor being replaced by a new card (ADR-19)") {
        `when`("the new card's restricted_id is recorded") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
            accountService.recordClaim(
                account.accountId,
                Claim(EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", ClaimSource("ident-eid")),
                provenAcr = AcrLevel.LOA2
            )
            accountService.recordClaim(
                account.accountId,
                Claim(EID_RESTRICTED_ID, "T0909090Z9X8Y7W6V5U4T3S2R1", ClaimSource("ident-eid")),
                provenAcr = AcrLevel.LOA2
            )

            then("the replace commits in place, like EMAIL - the account keeps exactly one") {
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE account_id = ? AND attribute_type = 'restricted_id'",
                    Int::class.java, account.accountId.value
                ) shouldBe 1
                jdbcTemplate.queryForObject(
                    "SELECT normalized_value FROM account.anchor WHERE account_id = ? AND attribute_type = 'restricted_id'",
                    String::class.java, account.accountId.value
                ) shouldBe "T0909090Z9X8Y7W6V5U4T3S2R1"
            }
        }
    }

    given("an eid restricted_id another account already holds") {
        `when`("a second account records the same restricted_id") {
            clearAccounts()
            val first = accountService.createAccountInSetup()
            val second = accountService.createAccountInSetup()
            accountService.recordClaim(
                first.accountId,
                Claim(EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", ClaimSource("ident-eid")),
                provenAcr = AcrLevel.LOA2
            )

            val result = runCatching {
                accountService.recordClaim(
                    second.accountId,
                    Claim(EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", ClaimSource("ident-eid")),
                    provenAcr = AcrLevel.LOA2
                )
            }

            then("the cross-account write refuses - a card pseudonym is never re-pointed to a second account") {
                shouldThrow<IdentityConflictException> { result.getOrThrow() }
                jdbcTemplate.queryForList(
                    "SELECT account_id FROM account.anchor WHERE attribute_type = 'restricted_id' AND normalized_value = ?",
                    Long::class.java, "T0103005K1D5S0V8T9W6UM2RTX"
                ) shouldBe listOf(first.accountId.value)
            }
        }
    }

    given("a method instance that asserted a module-owned value and an account-owned one") {
        `when`("the instance's claims are retracted") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
            val instanceId = java.util.UUID.randomUUID()
            accountService.recordClaims(
                account.accountId,
                listOf(
                    Claim(PHONE_NUMBER, "+491701234567", ClaimSource("enroll-sms")),
                    Claim(AttributeType.EMAIL, "max@example.com", ClaimSource("enroll-sms"))
                ),
                authMethodId = instanceId,
                provenAcr = AcrLevel.LOA2
            )

            val retracted = accountService.retractClaimsOf(
                account.accountId, instanceId.toString(), RetractionSource.ACCOUNT_MANAGEMENT
            )

            then("revoking it retracts only what the module owned") {
                retracted shouldBe 1

                // The phone number was the module's; the email is the account's own identity anchor and
                // survives - otherwise removing the sms method would take password login with it.
                jdbcTemplate.queryForObject(
                    "SELECT attribute_type FROM account.retraction WHERE account_id = ?",
                    String::class.java, account.accountId.value
                ) shouldBe "phone_number"
                accountService.findAccount(account.accountId)?.email shouldBe "max@example.com"
            }
        }
    }

    given("an anchor that was written") {
        `when`("a loa2 email claim is recorded from a loa1 session") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
            accountService.recordClaim(
                account.accountId,
                // The claim declares loa2; the session only ever proved loa1, and that is what counts.
                Claim(AttributeType.EMAIL, "capped@example.com", ClaimSource.SELF_REPORTED, AcrLevel.LOA2),
                provenAcr = AcrLevel.LOA1
            )

            then("it remembers the level the session actually proved, not the claim's own") {
                jdbcTemplate.queryForObject(
                    "SELECT established_acr FROM account.anchor WHERE account_id = ? AND attribute_type = 'email'",
                    String::class.java, account.accountId.value
                ) shouldBe "loa1"
            }
        }
    }

    // ADR-19 / ADR-12-Nachtrag: the claim log is a change log, not a run log. Re-attesting an
    // unchanged card costs nothing.
    given("a claim log that already established a tool's values") {
        `when`("the same card is attested again") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
            val eid = ClaimSource("ident-eid")
            val card = listOf(
                Claim(AttributeType.FAMILY_NAME, "Mustermann", eid, AcrLevel.LOA3),
                Claim(AttributeType.GIVEN_NAMES, "Max", eid, AcrLevel.LOA3),
                Claim(EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", eid, AcrLevel.LOA3)
            )
            accountService.recordClaims(account.accountId, card, provenAcr = AcrLevel.LOA2)
            accountService.recordClaims(account.accountId, card, provenAcr = AcrLevel.LOA2)

            then("re-attesting the same card logs nothing new (change log, not run log)") {
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.claim WHERE account_id = ?", Int::class.java, account.accountId.value
                ) shouldBe 3
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE account_id = ?", Int::class.java, account.accountId.value
                ) shouldBe 1
            }
        }
    }

    // ADR-20: the disposable account yields to the account the correlation step resolves. Needs the
    // real schema: `ux_anchor_value` is global, so the yielding account's anchors must be gone before
    // the same values are written on the absorbing one.
    given("a disposable account whose attestation resolves to another account") {
        `when`("it is absorbed into an identified account") {
            clearAccounts()
            val eid = ClaimSource("ident-eid")
            val disposable = accountService.createAccountInSetup()
            accountService.recordClaims(disposable.accountId, listOf(
                Claim(AttributeType.FAMILY_NAME, "Muster", eid, AcrLevel.LOA3),
                Claim(AttributeType.GIVEN_NAMES, "Max", eid, AcrLevel.LOA3),
                Claim(EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", eid, AcrLevel.LOA3)
            ), provenAcr = AcrLevel.LOA2)
            accountService.addIdentification(disposable.accountId, "eid", "loa3", role = "IDENTIFICATION", report = mapOf("provider" to "eid-mock-service"))
            val target = accountService.createAccountInSetup()
            accountService.recordClaim(
                target.accountId, Claim(AttributeType.PERSON_ID, "P000000001", ClaimSource.PERSON_DIRECTORY), provenAcr = AcrLevel.LOA2
            )

            accountService.absorbDisposableAccount(disposable.accountId, target.accountId)

            then("it yields: anchors and claims move, the identification proof is carried over, the account is gone") {
                accountService.findAccount(disposable.accountId).shouldBeNull()
                accountService.findAccount(target.accountId)!!.personId shouldBe PartnerNumber("P000000001")
                anchorRepository.findByAccountIdAndAttributeType(target.accountId, EID_RESTRICTED_ID)!!.value shouldBe
                    "T0103005K1D5S0V8T9W6UM2RTX"
                accountService.establishedClaimValues(target.accountId, setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES)) shouldBe
                    mapOf(AttributeType.FAMILY_NAME to "Muster", AttributeType.GIVEN_NAMES to "Max")
                // The proof of identity is the absorbing account's now (ADR-39): carried over, naming its origin.
                val carried = changeLogRepository.findByAccountIdAndChangeTypeOrderByOccurredAt(target.accountId, ChangeType.IDENTIFIED)
                    .single { it.subject == "eid" }.details!!
                carried["carriedFromAccountId"].toString() shouldBe disposable.accountId.toString()
                carried["provider"] shouldBe "eid-mock-service"
                carried["role"] shouldBe "IDENTIFICATION"
            }
        }

        `when`("it is absorbed into an account without that anchor") {
            clearAccounts()
            val eid = ClaimSource("ident-eid")
            val disposable = accountService.createAccountInSetup()
            accountService.recordClaim(
                disposable.accountId, Claim(AttributeType.EMAIL, "max@example.com", eid), provenAcr = AcrLevel.LOA2
            )
            val target = accountService.createAccountInSetup()

            accountService.absorbDisposableAccount(disposable.accountId, target.accountId)

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
    // placeholder an abandoned eID run left behind. Same port, arguments swapped; the disposable
    // account yields either way.
    given("a disposable leftover that an already-enrolled account identifies into") {
        `when`("the leftover is absorbed into the enrolled account") {
            clearAccounts()
            val eid = ClaimSource("ident-eid")
            val leftover = accountService.createAccountInSetup()
            accountService.recordClaim(
                leftover.accountId,
                Claim(EID_RESTRICTED_ID, "T0304223A9B1N7K5D2PN1S44QE", eid, AcrLevel.LOA3),
                provenAcr = AcrLevel.LOA2
            )
            val enrolled = accountService.createAccountInSetup()
            accountService.addAuthenticationMethod(
                enrolled.accountId, "password", EnrollmentRef("auth_password", "e-3"),
                enrolledUnderAcr = "loa1"
            )

            accountService.absorbDisposableAccount(leftover.accountId, enrolled.accountId)

            then("the leftover is absorbed into the account that holds the credentials") {
                accountService.findAccount(leftover.accountId).shouldBeNull()
                anchorRepository.findByAccountIdAndAttributeType(enrolled.accountId, EID_RESTRICTED_ID)!!.value shouldBe
                    "T0304223A9B1N7K5D2PN1S44QE"
                accountService.findAccount(enrolled.accountId)!!.activeAuthenticationMethods.size shouldBe 1
            }
        }
    }

    given("an anchor that a replacement claim re-points") {
        `when`("a new email replaces the old one") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
            accountService.recordClaim(
                account.accountId, Claim(AttributeType.EMAIL, "old@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA1
            )
            accountService.recordClaim(
                account.accountId, Claim(AttributeType.EMAIL, "new@example.com", ClaimSource.SELF_REPORTED), provenAcr = AcrLevel.LOA2
            )

            then("the anchor is re-pointed in place under the real unique constraints - the account keeps exactly one") {
                accountService.findAccountByEmail("old@example.com").shouldBeNull()
                accountService.findAccountByEmail("new@example.com")?.accountId shouldBe account.accountId
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE account_id = ? AND attribute_type = 'email'",
                    Int::class.java, account.accountId.value
                ) shouldBe 1
            }
            then("the old value is retracted so the log agrees with the anchor (ADR-19)") {
                accountService.establishedClaimValues(account.accountId, setOf(AttributeType.EMAIL)) shouldBe
                    mapOf(AttributeType.EMAIL to "new@example.com")
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.retraction WHERE account_id = ?", Int::class.java, account.accountId.value
                ) shouldBe 1
            }
        }

        `when`("the email goes from a to b and back to a") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
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
                    Int::class.java, account.accountId.value
                ) shouldBe 3
            }
        }
    }

    given("a card anchor replaced by a value that differs only in case") {
        `when`("the upper-case value is recorded") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
            val source = ClaimSource("ident-eid")
            accountService.recordClaim(account.accountId, Claim(EID_RESTRICTED_ID, "AbC123", source), provenAcr = AcrLevel.LOA3)
            accountService.recordClaim(account.accountId, Claim(EID_RESTRICTED_ID, "ABC123", source), provenAcr = AcrLevel.LOA3)

            then("the new claim stands - the replacement does not void what it just set") {
                accountService.establishedClaimValues(account.accountId, setOf(EID_RESTRICTED_ID))[EID_RESTRICTED_ID] shouldBe "ABC123"
            }
        }
    }

    given("a claim a tool reports at loa2, established from a session that only proved loa1 (ADR-5)") {
        `when`("the claim is recorded") {
            clearAccounts()
            val account = accountService.createAccountInSetup()
            accountService.recordClaims(
                account.accountId,
                listOf(Claim(PHONE_NUMBER, "+491701234567", ClaimSource("enroll-sms"), AcrLevel.LOA2)),
                provenAcr = AcrLevel.LOA1
            )

            then("the claim log records loa1 - what was proven, not the tool's ceiling") {
                jdbcTemplate.queryForObject(
                    "SELECT established_acr FROM account.claim WHERE account_id = ?", String::class.java, account.accountId.value
                ) shouldBe "loa1"
            }
        }
    }
})
