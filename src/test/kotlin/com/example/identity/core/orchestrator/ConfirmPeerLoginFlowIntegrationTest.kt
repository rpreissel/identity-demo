package com.example.identity.core.orchestrator

import com.example.identity.contract.texts.templateOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * `AuthIntent.CONFIRM_PEER_LOGIN` (docs/04-orchestrierung.md): the loa2 gate, and abort instead of
 * identification. Stops before `confirm-qr-login` activates; the approval itself is covered by
 * `AuthQrFlowIntegrationTest`, the abort on a cold device by `ConfirmPeerLoginStrategyTest`.
 */
class ConfirmPeerLoginFlowIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    init {
        given("a device already linked to an account with only sms enrolled (loa1)") {
            `when`("entering with intent=confirm_peer_login") {
                registerWithSmsOnly()

                val response = post("/orchestrator/api/v1/app/channels", """{"intent":"confirm_peer_login"}""")

                then("it gates on loa2 via the normal STEP_UP sub-journey, offering the account's own methods") {
                    response.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
                }
                then("the channel is not STEP_UP_IN_PROGRESS, since it was never logged in") {
                    // That state promises a cancel leads back to AUTHENTICATED (ChannelState.isLoggedIn).
                    response.channel()["state"] shouldBe "ANONYMOUS"
                }
            }

            `when`("the user cancels while the step-up sub-journey runs") {
                registerWithSmsOnly()
                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"confirm_peer_login"}""")
                    .channel()["channelSessionId"] as String

                val cancelled = delete("/orchestrator/api/v1/channels/$channelSessionId/journey")

                then("the channel falls back to not logged in - never AUTHENTICATED without a proof") {
                    // docs/invarianten.md I-4
                    cancelled.channel()["state"] shouldNotBe "AUTHENTICATED"
                }
                then("both the confirmation and its step-up are cancelled; the parent is not left SUSPENDED") {
                    jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM orchestrator.auth_journey WHERE channel_session_id = ? AND lifecycle = 'CANCELLED'",
                        Int::class.java, UUID.fromString(channelSessionId)
                    ) shouldBe 2
                }
            }

            `when`("the sms is proven, which still leaves the account under loa2 with no active method left to offer") {
                registerWithSmsOnly()
                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"confirm_peer_login"}""")
                    .channel()["channelSessionId"] as String
                val (tan, activation) = captureMockTan {
                    post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                }
                val authToolSessionId = activation.nextRaw()["toolSessionId"] as String

                val result = runCatching { patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""") }

                then("it aborts (410) instead of falling back to RE_IDENTIFY - a peer approval must never trigger identification") {
                    val gone = shouldThrow<HttpClientErrorException> { result.getOrThrow() }
                    gone.statusCode shouldBe HttpStatus.GONE
                    templateOf(gone.getResponseBodyAs(Map::class.java)!!["text"]) shouldContain "nicht erreichbar"
                }
            }
        }
    }
}
