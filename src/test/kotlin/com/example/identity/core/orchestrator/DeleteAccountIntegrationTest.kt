package com.example.identity.core.orchestrator

import java.util.UUID
import io.kotest.matchers.shouldBe

/**
 * Account deletion's ACR gate (docs/04-orchestrierung.md, DeleteAccountStrategy): the yes/no
 * confirmation comes first, then a step-up if the session lacks the level, falling back to
 * RE_IDENTIFY when no active method reaches it. loa2 for an identified account, loa1 for one never
 * identified (`Action.DeleteAccount.requiredAcr`). A proof of the last five minutes then deletes at
 * once; an older one asks for a fresh reconfirmation first.
 */
class DeleteAccountIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun accountCount(accountId: Any): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.account WHERE id = ?", Int::class.java, accountId)!!

    private fun accountOf(channelSessionId: String): Long =
        jdbcTemplate.queryForObject("SELECT account_id FROM orchestrator.channel_session WHERE id = ?", Long::class.java, channelSessionId)!!

    private fun startDeletion(channelSessionId: String): Map<String, Any?> =
        post("/orchestrator/api/v1/channels/$channelSessionId/account-deletions")

    private fun answer(channelSessionId: String, answer: String): Map<String, Any?> =
        post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"$answer"}""")

    /**
     * Seeds an sms-only account and logs in on a fresh channel, so the session holds loa1
     * possession only. Seeded via AccountFixtures: an HTTP registration would leave its journey open.
     */
    private fun loginWithSmsOnly(): Pair<Any, String> {
        val accountId = registerWithSmsOnly().value
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        authenticateViaSms(channelSessionId)
        return accountId to channelSessionId
    }

    init {
        given("an account authenticated via a single loa1-only factor (sms), no other active method") {
            `when`("the user starts deleting the account") {
                val (_, channelSessionId) = loginWithSmsOnly()
                val started = startDeletion(channelSessionId)

                then("the yes/no confirmation comes first") {
                    started.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                }
            }

            `when`("the user confirms the deletion") {
                val (_, channelSessionId) = loginWithSmsOnly()
                startDeletion(channelSessionId)
                val accepted = answer(channelSessionId, "accept")

                then("the loa2 gate offers RE_IDENTIFY, since no active method reaches loa2") {
                    accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                }
            }

            `when`("the user declines the gate's own RE_IDENTIFY (nested under its STEP_UP)") {
                val (accountId, channelSessionId) = loginWithSmsOnly()
                startDeletion(channelSessionId)
                answer(channelSessionId, "accept")
                val declined = answer(channelSessionId, "decline")

                // Cancelling hands back through two suspended parents in a row to DELETE_ACCOUNT. It
                // must not fall back to a reconfirmation that accepts the sms proof.
                then("the channel is back at AUTHENTICATED") {
                    declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    declined.channel()["state"] shouldBe "AUTHENTICATED"
                }
                then("the account is not deleted") {
                    accountCount(accountId) shouldBe 1
                }
            }
        }

        given("an authenticated account whose login is only moments old") {
            `when`("the user confirms the deletion") {
                val channelSessionId = loginAsSeededAccount()
                val accountId = accountOf(channelSessionId)
                startDeletion(channelSessionId)
                val accepted = answer(channelSessionId, "accept")

                then("the account is deleted at once, the login is the fresh proof") {
                    accepted.channel()["state"] shouldBe "LOGGED_OUT"
                    accountCount(accountId) shouldBe 0
                }
            }
        }

        given("an authenticated account whose login is ten minutes old") {
            `when`("the user confirms the deletion") {
                val channelSessionId = loginAsSeededAccount()
                ageProofs(UUID.fromString(channelSessionId), minutes = 10)
                startDeletion(channelSessionId)
                val accepted = answer(channelSessionId, "accept")

                then("a fresh reconfirmation is asked for") {
                    accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                }
            }

            `when`("the user reconfirms with the password") {
                val channelSessionId = loginAsSeededAccount()
                val accountId = accountOf(channelSessionId)
                ageProofs(UUID.fromString(channelSessionId), minutes = 10)
                startDeletion(channelSessionId)
                answer(channelSessionId, "accept")
                val completed = authenticateViaPassword(channelSessionId)

                then("the completion response already reports LOGGED_OUT, not AUTHENTICATED") {
                    completed.channel()["state"] shouldBe "LOGGED_OUT"
                    completed["next"] shouldBe null
                }
                then("the account is deleted") {
                    accountCount(accountId) shouldBe 0
                }
            }
        }
    }
}
