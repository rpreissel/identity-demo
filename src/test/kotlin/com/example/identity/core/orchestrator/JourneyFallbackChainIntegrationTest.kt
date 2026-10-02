package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * The properties of the per-intent state machine (docs/04-orchestrierung.md): the FAST fallback chain
 * and its memory of what was declined, identification as a LOGIN path when nothing else is left, the
 * states LOGIN_LOOKUP does not have, and the journey-wide attempt budget. The happy paths of each
 * intent are in RegistrationFlowIntegrationTest and LoginFlowIntegrationTest.
 */
class JourneyFallbackChainIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeScenario { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("an account with sms only, bound to this device") {
            // Fallback chain: the transition itself is FastAccessStrategyTest's; here the round trip.
            `when`("a fresh channel declines its only auth method and identifies via ident-fsc") {
                val accountId = registerWithSmsOnly()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val authToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String

                val afterDecline = delete("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms")
                val identified = reIdentifyViaFsc(channelSessionId)

                then("declining is not a dead end: the chain falls through to identification") {
                    afterDecline.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                    // shouldContainAll, not exact: identification is the point, not the catalog's exact set.
                    @Suppress("UNCHECKED_CAST")
                    (afterDecline.stepData()["options"] as List<String>) shouldContainAll listOf("ident-fsc", "ident-eid")
                }
                then("the same KVNR logs into the same account, without registering a second one") {
                    // "registration" versus "login" is not chosen up front, it describes the path taken.
                    identified.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.account", Int::class.java) shouldBe 1
                    jdbcTemplate.queryForObject("SELECT MIN(id) FROM account.account", Long::class.java) shouldBe accountId.value
                }
            }

            `when`("a lookup login names ident-fsc directly") {
                registerWithSmsOnly()
                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                    .channel()["channelSessionId"] as String

                val result = runCatching { post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc") }

                then("it is rejected at the boundary - no state of this intent offers an identification") {
                    // Instead of failing deeper with a 500.
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
            }

            `when`("two wrong TANs go to one tool session and a third to a fresh one") {
                registerWithSmsOnly()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val firstToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"000000"}""")
                patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"000000"}""")
                val secondToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String

                val result = runCatching { patch("/orchestrator/api/v1/tools/$secondToolSessionId/auth-sms", """{"tan":"000000"}""") }

                then("the attempt budget spans the whole journey, not a single tool: the process ends as 410") {
                    // A per-tool counter would start over at zero, and the journey would survive indefinitely.
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.GONE
                }
            }

            `when`("a lookup login on another device is abandoned and cancelled") {
                registerWithSmsOnly()
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                    .channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms-lookup").nextRaw()["toolSessionId"] as String
                delete("/orchestrator/api/v1/tools/$toolSessionId/auth-sms-lookup")

                val afterCancel = delete("/orchestrator/api/v1/channels/$channelSessionId/journey")

                then("it restarts a lookup login, not a registration - the channel remembers its entry intent") {
                    afterCancel.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    // shouldContainAll, not exact: the lookup intent is preserved, not the catalog's exact lookup-tool set.
                    @Suppress("UNCHECKED_CAST")
                    (afterCancel.stepData()["options"] as List<String>) shouldContainAll
                        listOf("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup", "auth-qr-lookup")
                }
            }
        }
    }
}
