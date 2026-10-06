package com.example.identity.core.account

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.core.account.application.ClaimBatchKeyRetention
import com.example.identity.core.account.infrastructure.ChangeLogRepository
import com.example.identity.core.account.infrastructure.ChangeType
import com.example.identity.core.orchestrator.SharedSpringContext
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import java.sql.Timestamp
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate

/**
 * ADR-52 against the real schema: nothing readable in `account.claim` and `account.retraction`,
 * the SQL subtraction works on digests, and the retention sweep ends a batch as one transaction.
 */
class ClaimEncryptionDbTest(
    private val accountService: AccountService,
    private val retention: ClaimBatchKeyRetention,
    private val changeLogRepository: ChangeLogRepository,
    private val jdbcTemplate: JdbcTemplate,
) : SharedSpringContext({

    // Runs first in every `when`: beforeEach would only precede the `then` leaves, after the action.
    fun clearAccounts() {
        jdbcTemplate.update("DELETE FROM account.account")
    }

    val eid = ClaimSource("ident-eid")
    val names = setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES)

    fun batchKeys(): Int = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim_batch_key", Int::class.java)!!

    given("an identified account") {
        `when`("its claims are recorded") {
            clearAccounts()
            val account = accountService.createAccountInSetup().accountId
            accountService.recordClaims(account, listOf(
                Claim(AttributeType.FAMILY_NAME, "Muster", eid, AcrLevel.LOA3),
                Claim(AttributeType.GIVEN_NAMES, "Max", eid, AcrLevel.LOA3),
            ), provenAcr = AcrLevel.LOA3)

            then("the table holds neither the value nor its normalized form") {
                jdbcTemplate.queryForList("SELECT claim_value, value_digest FROM account.claim WHERE account_id = ?", account.value).forEach { row ->
                    String(row["CLAIM_VALUE"] as ByteArray, Charsets.ISO_8859_1) shouldNotContain "Muster"
                    String(row["CLAIM_VALUE"] as ByteArray, Charsets.ISO_8859_1) shouldNotContain "Max"
                    (row["VALUE_DIGEST"] as String).lowercase() shouldNotContain "muster"
                }
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.account WHERE id = ? AND wrapped_master_key IS NOT NULL", Int::class.java, account.value) shouldBe 1
            }

            then("one batch key serves the run, and the values read back") {
                batchKeys() shouldBe 1
                accountService.establishedClaimValues(account, names) shouldBe mapOf(AttributeType.FAMILY_NAME to "Muster", AttributeType.GIVEN_NAMES to "Max")
            }
        }

        `when`("the only claim of a batch is retracted") {
            clearAccounts()
            val account = accountService.createAccountInSetup().accountId
            accountService.recordClaims(account, listOf(Claim(AttributeType.FAMILY_NAME, "Muster", eid, AcrLevel.LOA3)), provenAcr = AcrLevel.LOA3)
            accountService.retractAttribute(account, AttributeType.FAMILY_NAME, RetractionSource.OPERATOR, "test")

            then("the batch key is gone, the claim row stays as metadata, the retraction holds no plaintext") {
                batchKeys() shouldBe 0
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.claim WHERE account_id = ?", Int::class.java, account.value) shouldBe 1
                jdbcTemplate.queryForObject("SELECT value_digest FROM account.retraction WHERE account_id = ?", String::class.java, account.value)!!.lowercase() shouldNotContain "muster"
                accountService.establishedClaimValues(account, names) shouldBe emptyMap()
            }
        }

        `when`("the account is deleted") {
            clearAccounts()
            val account = accountService.createAccountInSetup().accountId
            accountService.recordClaims(account, listOf(Claim(AttributeType.FAMILY_NAME, "Muster", eid, AcrLevel.LOA3)), provenAcr = AcrLevel.LOA3)
            accountService.deleteAccount(account)

            then("its batch keys go with it") {
                batchKeys() shouldBe 0
            }
        }
    }

    given("a batch whose retention ran out") {
        `when`("the retention sweep runs") {
            clearAccounts()
            val account = accountService.createAccountInSetup().accountId
            accountService.recordClaims(account, listOf(
                Claim(AttributeType.FAMILY_NAME, "Muster", eid, AcrLevel.LOA3),
                Claim(AttributeType.GIVEN_NAMES, "Max", eid, AcrLevel.LOA3),
            ), provenAcr = AcrLevel.LOA3)
            accountService.recordClaims(account, listOf(Claim(AttributeType.LOCALITY, "Musterstadt", eid, AcrLevel.LOA3)), provenAcr = AcrLevel.LOA3)
            // No rule is configured in the test profile, so the expiry is set by hand - the sweep reads only this column.
            val expiredAt = Instant.parse("2026-01-01T00:00:00Z")
            jdbcTemplate.update(
                "UPDATE account.claim_batch_key SET expires_at = ? WHERE claim_batch_id = (SELECT claim_batch_id FROM account.claim WHERE account_id = ? AND attribute_type = 'family_name')",
                Timestamp.from(expiredAt), account.value
            )
            val keptBefore = retention.purge(expiredAt.minusSeconds(1))

            val erased = retention.purge(expiredAt.plusSeconds(1))

            then("nothing happens before the expiry, afterwards the batch is withdrawn by the policy and its key deleted") {
                keptBefore shouldBe 0
                erased shouldBe 1
                batchKeys() shouldBe 1
                accountService.establishedClaimValues(account, names + AttributeType.LOCALITY) shouldBe mapOf(AttributeType.LOCALITY to "Musterstadt")
                jdbcTemplate.queryForList("SELECT claim_source FROM account.retraction WHERE account_id = ?", String::class.java, account.value).toSet() shouldBe setOf("RETENTION_POLICY")
                changeLogRepository.findByAccountIdOrderByOccurredAt(account).filter { it.changeType == ChangeType.ATTRIBUTE_RETRACTED }.size shouldBe 2
            }
        }
    }
})
