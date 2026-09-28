package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.nulls.shouldBeNull
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
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("an account registered via 'Enrollment zuerst', never identified (personId == null)") {
            then("loa1 already satisfies the gate - no STEP_UP to loa2, straight to the re-confirmation step") {
                put("/orchestrator/admin/registration-order", """{"enrollFirst":true}""") shouldBe org.springframework.http.HttpStatus.OK

                val channelSessionId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                confirmEmail(channelSessionId)
                enrollSms(channelSessionId)
                enrollPassword(channelSessionId)
                // Declining the closing identification offer keeps personId == null.
                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                declined.channel()["state"] shouldBe "AUTHENTICATED"

                val accountId = jdbcTemplate.queryForObject(
                    "SELECT account_id FROM orchestrator.channel_session WHERE id = ?",
                    Long::class.java,
                    channelSessionId
                )
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE account_id = ? AND attribute_type = 'person_id'",
                    Int::class.java,
                    accountId
                ) shouldBe 0

                val started = post("/orchestrator/api/v1/channels/$channelSessionId/account-deletions")
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                // Straight to the re-confirmation offer: the session's loa1 already suffices.
                accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")

                val activation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password")
                val toolSessionId = activation.nextRaw()["toolSessionId"] as String
                val completed = patch("/orchestrator/api/v1/tools/$toolSessionId/auth-password", """{"password":"correct-horse-battery"}""")

                completed.channel()["state"] shouldBe "LOGGED_OUT"
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.account WHERE id = ?",
                    Int::class.java,
                    accountId
                ) shouldBe 0
            }
        }
    }

    init {
        given("an account authenticated via a single loa1-only factor (sms), no other active method") {
            then("declining the gate's own RE_IDENTIFY (nested under its STEP_UP) must NOT delete the account") {
                // With a single loa1 factor, the loa2 gate must go through STEP_UP -> RE_IDENTIFY.
                // Cancelling then has to hand back through two suspended parents in a row.
                // Seeded via AccountFixtures: an HTTP registration would leave its journey open.
                val accountId = registerWithSmsOnly()

                // A fresh channel logs in via sms alone, so the session holds loa1 possession only.
                val newChannel = post("/orchestrator/api/v1/app/channels")
                newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
                val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
                val authenticated = authenticateViaSms(newChannelSessionId)
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                // Konto löschen -> confirm -> loa2 gate not satisfied (sms alone is loa1) -> STEP_UP
                // -> no active method reaches loa2 either -> RE_IDENTIFY offered.
                val started = post("/orchestrator/api/v1/channels/$newChannelSessionId/account-deletions")
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                val accepted = post("/orchestrator/api/v1/channels/$newChannelSessionId/answer", """{"answer":"accept"}""")
                accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                // Declining RE_IDENTIFY cancels up the chain to DELETE_ACCOUNT, which ends in
                // AUTHENTICATED. It must not fall back to a reconfirmation that accepts the sms proof.
                val declined = post("/orchestrator/api/v1/channels/$newChannelSessionId/answer", """{"answer":"decline"}""")
                declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                declined.channel()["state"] shouldBe "AUTHENTICATED"
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.account WHERE id = ?",
                    Int::class.java,
                    accountId
                ) shouldBe 1
            }
        }

        given("an authenticated account deleting itself after fresh reconfirmation") {
            then("the completion response already reports LOGGED_OUT, not AUTHENTICATED") {
                val channelSessionId = loginAsSeededAccount()
                val accountId = jdbcTemplate.queryForObject(
                    "SELECT account_id FROM orchestrator.channel_session WHERE id = ?",
                    Long::class.java,
                    channelSessionId
                )

                val started = post("/orchestrator/api/v1/channels/$channelSessionId/account-deletions")
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")

                val activation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password")
                val toolSessionId = activation.nextRaw()["toolSessionId"] as String
                val completed = patch("/orchestrator/api/v1/tools/$toolSessionId/auth-password", """{"password":"correct-horse-battery"}""")

                completed.channel()["state"] shouldBe "LOGGED_OUT"
                completed["next"] shouldBe null
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.account WHERE id = ?",
                    Int::class.java,
                    accountId
                ) shouldBe 0
            }
        }
    }
}
