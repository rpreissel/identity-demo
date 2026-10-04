package com.example.identity.core.account

import com.example.identity.core.orchestrator.SharedSpringContext
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.infrastructure.ChangeLogEntry
import com.example.identity.core.account.infrastructure.ChangeLogRepository
import com.example.identity.core.account.application.ChangeLogRetention
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.EnrollmentRef
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * ADR-39: what an account deletion leaves behind is the change log without values - that and how,
 * never what - and only until the retention period since the deletion has passed.
 */
class ChangeLogDbTest(
    private val accountService: AccountService,
    private val changeLogRetention: ChangeLogRetention,
    private val changeLogSearch: ChangeLogSearch,
    private val changeLogRepository: ChangeLogRepository,
    private val jdbcTemplate: JdbcTemplate,
) : SharedSpringContext({

    // Runs first in every `when`: beforeEach would only precede the `then` leaves, after the action.
    fun clearAccountsAndLog() {
        jdbcTemplate.update("DELETE FROM account.account")
        jdbcTemplate.update("DELETE FROM account.change_log")
    }

    fun events(accountId: AccountId): List<ChangeLogEntry> = changeLogRepository.findByAccountIdOrderByOccurredAt(accountId)

    fun livedThrough(accountId: AccountId) {
        accountService.addIdentification(accountId, "ident-fsc", "loa2", role = "IDENTIFICATION",
            report = mapOf("provider" to "fsc-service", "providerTxId" to "FSC-1", "documentNumber" to "C01X00T47"), tool = "ident-fsc@1")
        val method = accountService.addAuthenticationMethod(
            accountId, "sms", EnrollmentRef("auth_sms.enrollment", "1"), enrolledUnderAcr = "loa1",
            enrolledUnderAmr = listOf("email", "password"), channel = "WEB", tool = "enroll-sms@1"
        ).activeAuthenticationMethods.single()
        accountService.deactivateAuthenticationMethod(accountId, method.id)
        accountService.deleteAccount(accountId)
    }

    fun yearsFromNow(years: Long): Instant = Instant.now().atZone(ZoneOffset.UTC).plusYears(years).toInstant()

    given("an account that was identified, got a method and lost it") {
        `when`("the account is deleted") {
            clearAccountsAndLog()
            val accountId = accountService.createAccountInSetup().accountId

            livedThrough(accountId)

            then("the trail survives the deletion - names and levels only, no value, while the value tables are gone") {
                val trail = events(accountId)
                trail.map { it.changeType.name } shouldBe listOf("IDENTIFIED", "METHOD_ADDED", "METHOD_DEACTIVATED", "ACCOUNT_DELETED")
                trail[0].subject shouldBe "ident-fsc"
                trail[0].acr shouldBe "loa2"
                // Every row names its own type and version, so it explains itself without this code.
                trail.forEach { it.details!!["type"] shouldBe it.changeType.name; it.details!!["version"] shouldBe it.changeType.detailsVersion }
                // The references are kept, the document number the tool reported is not (§ 20 PAuswG).
                trail[0].details!!["providerTxId"] shouldBe "FSC-1"
                // Which tool in which version the client spoke (ADR-51).
                trail[0].details!!["tool"] shouldBe "ident-fsc@1"
                trail[1].details!!["tool"] shouldBe "enroll-sms@1"
                trail[0].details!!.containsKey("documentNumber") shouldBe false
                // How the method was added outlives it: the session's proofs and the channel.
                trail[1].details!!["amr"] shouldBe listOf("email", "password")
                trail[1].details!!["channel"] shouldBe "WEB"
                trail[2].details!!["reason"] shouldBe "REMOVED_BY_HOLDER"
                // No value of the account made it into the trail.
                trail.none { it.details.toString().contains("+49") } shouldBe true
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.auth_method WHERE account_id = ?", Int::class.java, accountId.value) shouldBe 0
            }
        }
    }

    given("the trail of a deleted account and the retention period (10 years by default)") {
        `when`("the retention purge runs nine years after the deletion") {
            clearAccountsAndLog()
            val accountId = accountService.createAccountInSetup().accountId
            livedThrough(accountId)

            val purged = changeLogRetention.purge(yearsFromNow(9))

            then("the trail is kept within the period") {
                purged shouldBe 0
                events(accountId).size shouldBe 4
            }
        }

        `when`("the retention purge runs eleven years after the deletion") {
            clearAccountsAndLog()
            val accountId = accountService.createAccountInSetup().accountId
            livedThrough(accountId)

            val purged = changeLogRetention.purge(yearsFromNow(11))

            then("the trail is deleted once the period has passed") {
                purged shouldBe 4
                events(accountId).size shouldBe 0
            }
        }
    }

    given("the trail of an account that still exists") {
        `when`("the retention purge runs fifty years later") {
            clearAccountsAndLog()
            val accountId = accountService.createAccountInSetup().accountId
            accountService.addIdentification(accountId, "ident-fsc", "loa2", null)

            val purged = changeLogRetention.purge(yearsFromNow(50))

            then("the trail is never touched") {
                purged shouldBe 0
                events(accountId).size shouldBe 1
            }
        }
    }

    fun identifiedAndDeleted(source: ClaimSource, personId: PartnerNumber?): AccountId {
        val accountId = accountService.createAccountInSetup().accountId
        accountService.recordClaims(
            accountId,
            listOfNotNull(
                Claim(AttributeType.FAMILY_NAME, "Müller", source, AcrLevel.LOA2),
                Claim(AttributeType.GIVEN_NAMES, "Max", source, AcrLevel.LOA2),
                Claim(AttributeType.BIRTH_DATE, "1985-06-15", source, AcrLevel.LOA2),
                personId?.let { Claim(AttributeType.PERSON_ID, it.value, ClaimSource.PERSON_DIRECTORY, AcrLevel.LOA2) },
            ),
            provenAcr = AcrLevel.LOA2
        )
        accountService.addIdentification(accountId, "ident-eid", "loa3", role = "IDENTIFICATION", report = mapOf("provider" to "eid-mock-service"))
        accountService.deleteAccount(accountId)
        return accountId
    }

    given("a person identified once by a tool, whose account was deleted since") {
        `when`("the trail is searched by name, first name and date of birth") {
            clearAccountsAndLog()
            val accountId = identifiedAndDeleted(ClaimSource("ident-eid"), personId = null)

            val found = changeLogSearch.byPerson("Müller", "Max", LocalDate.parse("1985-06-15"))

            then("these three alone find it, without a person id") {
                found.map { it.accountId }.distinct() shouldBe listOf(accountId)
                found.map { it.changeType } shouldBe listOf("IDENTIFIED", "ACCOUNT_DELETED")
            }
        }
    }

    given("a person the register knew by person id, whose account was deleted since") {
        `when`("the trail is searched by that person id") {
            clearAccountsAndLog()
            val accountId = identifiedAndDeleted(ClaimSource.PERSON_DIRECTORY, personId = PartnerNumber("P000000042"))

            val found = changeLogSearch.byPersonId(PartnerNumber("P000000042"))

            then("the register's person id finds it too") {
                found.map { it.accountId }.distinct() shouldBe listOf(accountId)
            }
        }
    }

    given("a person who only reported their names themselves, whose account was deleted since") {
        `when`("the trail is searched by name, first name and date of birth") {
            clearAccountsAndLog()
            identifiedAndDeleted(ClaimSource.SELF_REPORTED, personId = null)

            val found = changeLogSearch.byPerson("Müller", "Max", LocalDate.parse("1985-06-15"))

            then("nothing is found - otherwise anyone could plant hits under someone else's name") {
                found.shouldBeEmpty()
            }
        }
    }
})
