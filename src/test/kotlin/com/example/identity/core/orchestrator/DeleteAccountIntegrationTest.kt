package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe

/**
 * Account deletion's ACR gate (docs/04-orchestrierung.md, DeleteAccountStrategy): the yes/no
 * confirmation comes first, then a step-up if the session lacks the level, falling back to
 * RE_IDENTIFY when no active method reaches it. loa2 for an identified account, loa1 for one never
 * identified (`Action.DeleteAccount.requiredAcr`).
 */
class DeleteAccountIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeScenario { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun accountCount(accountId: Any): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.account WHERE id = ?", Int::class.java, accountId)!!

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

        given("an authenticated account deleting itself after fresh reconfirmation") {
            `when`("the user confirms the deletion") {
                val channelSessionId = loginAsSeededAccount()
                startDeletion(channelSessionId)
                val accepted = answer(channelSessionId, "accept")

                then("a fresh reconfirmation is asked for") {
                    accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                }
            }

            `when`("the user reconfirms with the password") {
                val channelSessionId = loginAsSeededAccount()
                val accountId = jdbcTemplate.queryForObject(
                    "SELECT account_id FROM orchestrator.channel_session WHERE id = ?",
                    Long::class.java,
                    channelSessionId
                )!!
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
