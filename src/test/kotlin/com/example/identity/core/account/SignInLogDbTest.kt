package com.example.identity.core.account

import com.example.identity.core.orchestrator.SharedSpringContext
import com.example.identity.core.account.infrastructure.SignInLogRepository
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-39, addendum: the sign-in log belongs to the account - it goes with the account, and it is
 * kept for months, not years.
 */
class SignInLogDbTest(
    private val accountService: AccountService,
    private val signInLog: SignInLog,
    private val retention: SignInLogRetention,
    private val repository: SignInLogRepository,
    private val jdbcTemplate: JdbcTemplate,
) : SharedSpringContext({

    // Runs first in every `when`: beforeEach would only precede the `then` leaves, after the action.
    fun clearAccounts() {
        jdbcTemplate.update("DELETE FROM account.account")
    }

    given("an account that signed in") {
        `when`("the account is deleted") {
            clearAccounts()
            val accountId = accountService.createAccountInSetup().accountId
            signInLog.signedIn(accountId, "APP", "loa2", listOf("sms", "password"), listOf("auth-sms@1"), "FAST_ACCESS")
            val loggedBefore = signInLog.of(accountId).size

            accountService.deleteAccount(accountId)

            then("its sign-in log goes with it - unlike the change log, it is no proof beyond the deletion") {
                loggedBefore shouldBe 1
                repository.findByAccountIdOrderByOccurredAt(accountId).size shouldBe 0
            }
        }

        // The retention period is 6 months by default.
        `when`("the retention purge runs five months later") {
            clearAccounts()
            val accountId = accountService.createAccountInSetup().accountId
            signInLog.signedIn(accountId, "APP", "loa2", listOf("sms"), listOf("auth-sms@1"), "FAST_ACCESS")

            val purged = retention.purge(Instant.now().atZone(ZoneOffset.UTC).plusMonths(5).toInstant())

            then("the entry is kept within the period") {
                purged shouldBe 0
                signInLog.of(accountId).size shouldBe 1
            }
        }

        `when`("the retention purge runs seven months later") {
            clearAccounts()
            val accountId = accountService.createAccountInSetup().accountId
            signInLog.signedIn(accountId, "APP", "loa2", listOf("sms"), listOf("auth-sms@1"), "FAST_ACCESS")

            val purged = retention.purge(Instant.now().atZone(ZoneOffset.UTC).plusMonths(7).toInstant())

            then("the entry is deleted after the period") {
                purged shouldBe 1
                signInLog.of(accountId).size shouldBe 0
            }
        }
    }
})
