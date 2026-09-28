package com.example.identity.core.account

import com.example.identity.core.account.infrastructure.SignInLogRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.time.ZoneOffset

/**
 * ADR-39, addendum: the sign-in log belongs to the account - it goes with the account, and it is
 * kept for months, not years.
 */
@SpringBootTest
@ActiveProfiles("test")
class SignInLogDbTest(
    private val accountService: AccountService,
    private val signInLog: SignInLog,
    private val retention: SignInLogRetention,
    private val repository: SignInLogRepository,
    private val jdbcTemplate: JdbcTemplate,
) : BehaviorSpec({

    // Runs first in every `when`: beforeEach would only precede the `then` leaves, after the action.
    fun clearAccounts() {
        jdbcTemplate.update("DELETE FROM account.account")
    }

    given("an account that signed in") {
        `when`("the account is deleted") {
            clearAccounts()
            val accountId = accountService.createUnidentifiedAccount().accountId
            signInLog.signedIn(accountId, "APP", "loa2", listOf("sms", "password"), "FAST_ACCESS")
            val loggedBefore = signInLog.of(accountId).size

            accountService.deleteAccount(accountId)

            then("its sign-in log goes with it - unlike the change log, it is no proof beyond the deletion") {
                loggedBefore shouldBe 1
                repository.findByAccountIdOrderByOccurredAt(accountId).size shouldBe 0
            }
        }

        `when`("the retention purge runs five and then seven months later") {
            clearAccounts()
            val accountId = accountService.createUnidentifiedAccount().accountId
            signInLog.signedIn(accountId, "APP", "loa2", listOf("sms"), "FAST_ACCESS")

            val purgedWithin = retention.purge(Instant.now().atZone(ZoneOffset.UTC).plusMonths(5).toInstant())
            val purgedAfter = retention.purge(Instant.now().atZone(ZoneOffset.UTC).plusMonths(7).toInstant())

            then("the retention period (6 months by default) keeps it within and deletes it after") {
                purgedWithin shouldBe 0
                purgedAfter shouldBe 1
                signInLog.of(accountId).size shouldBe 0
            }
        }
    }
})
