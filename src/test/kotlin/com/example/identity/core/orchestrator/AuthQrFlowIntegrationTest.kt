package com.example.identity.core.orchestrator

import com.example.identity.contract.texts.templateOf
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import org.springframework.web.client.HttpClientErrorException
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.example.identity.core.orchestrator.kc.PeerAuthAssertion
import com.example.identity.core.orchestrator.kc.PeerAuthValidator
import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus

/**
 * Cross-channel QR login (docs/04-orchestrierung.md, CONFIRM_PEER_LOGIN): a WEB `auth-qr-lookup`/`auth-qr`
 * activation is resolved by an authenticated APP channel's `confirm-qr-login`. Both sides run in
 * this process; only peer-auth is mocked (as in [KcChannelIntegrationTest]).
 */
class AuthQrFlowIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeScenario { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun stubAssertion(channelBinding: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns PeerAuthAssertion(
            jti = UUID.randomUUID().toString(),
            issuedAt = Instant.now(),
            channelBinding = channelBinding,
            subject = null
        )
    }

    private fun kcHeaders(): HttpHeaders = HttpHeaders().apply {
        set("Authorization", "Bearer mock-peer-auth-token")
        set("Content-Type", "application/json")
    }

    private fun kcPatch(channelSessionId: ChannelSessionId, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/kc/channels/$channelSessionId",
            HttpMethod.PATCH,
            HttpEntity(withDefaultAvailableTools(body), kcHeaders()),
            mapType
        ).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    private fun kcPost(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.POST, HttpEntity(body, kcHeaders()), mapType)
            .let { it.statusCode.is2xxSuccessful shouldBe true; it.body!! }

    private fun kcPatchTool(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, kcHeaders()), mapType)
            .let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    /** The read-only GET the waiting page's status check uses (docs/05-api.md, Peer-Login bestätigen). */
    private fun kcGetTool(url: String): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.GET, HttpEntity<Unit>(kcHeaders()), mapType)
            .let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    /** Registers+authenticates on the APP channel, then enrolls the qr opt-in on the same, still-AUTHENTICATED channel. */
    private fun registerWithQrOptIn(): Pair<String, Long> {
        val channelSessionId = loginAsSeededAccount()
        val accountId = jdbcTemplate.queryForObject(
            "SELECT id FROM account.account ORDER BY id DESC LIMIT 1", Long::class.java
        )!!

        post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-qr").nextRaw()["toolSessionId"] as String
        val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-qr", "{}")
        enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

        return channelSessionId to accountId
    }

    /**
     * CONFIRM_PEER_LOGIN demands one fresh factor before `confirm-qr-login`, even though the
     * channel already reaches loa2 (docs/04-orchestrierung.md). Resolved via auth-sms.
     */
    private fun resolveReconfirmation(appChannelSessionId: String) {
        val resolved = authenticateViaSms(appChannelSessionId)
        resolved.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-qr-login", "step" to "input")
    }

    /** Activates auth-qr-lookup on a fresh WEB channel, returns (toolSessionId, pairingCode). */
    private fun startWebLookup(): Pair<String, String> {
        val webChannelSessionId = ChannelSessionId(UUID.randomUUID())
        stubAssertion(channelBinding = webChannelSessionId.toString())
        kcPatch(webChannelSessionId)
        val webToolSessionId = kcPost("/orchestrator/api/v1/channels/$webChannelSessionId/tools/auth-qr-lookup")
            .nextRaw()["toolSessionId"] as String
        val waiting = kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")
        val pairingCode = waiting.stepData()["pairingCode"] as String
        return webToolSessionId to pairingCode
    }

    /**
     * Drives the APP side up to and including the approval; returns (accountId, confirmationCode) -
     * the code the app shows, once, to be typed into the browser.
     */
    private fun approveOnApp(pairingCode: String): Pair<Long, String> {
        val (appChannelSessionId, accountId) = registerWithQrOptIn()
        post("/orchestrator/api/v1/channels/$appChannelSessionId/peer-logins")
        resolveReconfirmation(appChannelSessionId)
        val confirmToolSessionId =
            post("/orchestrator/api/v1/channels/$appChannelSessionId/tools/confirm-qr-login").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"pairingCode":"$pairingCode"}""")
        val shown = patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"decision":"accept"}""")
        shown.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-qr-login", "step" to "showCode")
        return accountId to (shown.stepData()["confirmationCode"] as String)
    }

    init {
        given("an account with the qr opt-in, and a WEB channel waiting on auth-qr-lookup") {
            `when`("that same account confirms via confirm-qr-login on its own authenticated channel and the browser enters the code") {
                val (webToolSessionId, pairingCode) = startWebLookup()
                val (appChannelSessionId, accountId) = registerWithQrOptIn()
                val beforeApproval = kcGetTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")

                val started = post("/orchestrator/api/v1/channels/$appChannelSessionId/peer-logins")
                resolveReconfirmation(appChannelSessionId)
                val confirmToolSessionId =
                    post("/orchestrator/api/v1/channels/$appChannelSessionId/tools/confirm-qr-login").nextRaw()["toolSessionId"] as String
                val confirmStep = patch(
                    "/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login",
                    """{"pairingCode":"$pairingCode"}"""
                )
                val shown = patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"decision":"accept"}""")
                val confirmationCode = shown.stepData()["confirmationCode"] as String

                val afterApproval = kcGetTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")
                val asking = kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")
                val resolved = kcPatchTool(
                    "/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup",
                    """{"confirmationCode":"$confirmationCode"}"""
                )
                val done = patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"decision":"done"}""")

                then("the browser waits for the app until the approval") {
                    beforeApproval.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-qr-lookup", "step" to "waitForApp")
                }
                then("the app's peer login asks for a fresh factor, then for the pairing code and the decision") {
                    started.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    confirmStep.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-qr-login", "step" to "confirm")
                }
                then("the approval shows a confirmation code on the app") {
                    shown.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-qr-login", "step" to "showCode")
                }
                then("approving alone logs no browser in: the read and the poll both ask for the code") {
                    // The read shows the approval without deciding anything.
                    afterApproval.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-qr-lookup", "step" to "enterCode")
                    asking.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-qr-lookup", "step" to "enterCode")
                }
                then("only that code typed into the browser logs it in, as the approving account") {
                    resolved.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    (resolved["authData"] as Map<*, *>)["subject"] shouldBe mapOf("type" to "account", "id" to accountId.toString())
                }
                then("the app finishes its confirmation") {
                    done.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                }
            }
        }

        given("an approved pairing whose browser does not know the code - a victim approving from a phishing link") {
            `when`("the browser guesses three wrong codes") {
                val (webToolSessionId, pairingCode) = startWebLookup()
                val (_, confirmationCode) = approveOnApp(pairingCode)
                val wrong = if (confirmationCode == "000000") "000001" else "000000"

                val guesses = List(2) {
                    kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup", """{"confirmationCode":"$wrong"}""")
                }
                val third = runCatching {
                    kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup", """{"confirmationCode":"$wrong"}""")
                }

                then("the first two fail without logging in") {
                    guesses.forEach { it.channel()["state"] shouldNotBe "AUTHENTICATED" }
                }
                then("the third exhausts the journey's attempt budget: the login is aborted") {
                    shouldThrow<HttpClientErrorException> { third.getOrThrow() }.statusCode shouldBe HttpStatus.GONE
                }
                then("the request is burned") {
                    jdbcTemplate.queryForObject(
                        "SELECT status FROM auth_qr.login_request WHERE pairing_code = ?", String::class.java, pairingCode
                    ) shouldBe "EXPIRED"
                }
            }
        }

        given("a pending pairing") {
            `when`("the APP side declines and the WEB side reads twice and polls") {
                val (webToolSessionId, pairingCode) = startWebLookup()
                val (appChannelSessionId, _) = registerWithQrOptIn()

                post("/orchestrator/api/v1/channels/$appChannelSessionId/peer-logins")
                resolveReconfirmation(appChannelSessionId)
                val confirmToolSessionId =
                    post("/orchestrator/api/v1/channels/$appChannelSessionId/tools/confirm-qr-login").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"pairingCode":"$pairingCode"}""")
                val declined = patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-qr-login", """{"decision":"reject"}""")

                val reads = List(2) { kcGetTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup") }
                val poll = kcPatchTool("/orchestrator/api/v1/tools/$webToolSessionId/auth-qr-lookup")

                then("the app side reports the rejection") {
                    templateOf(declined.stepData()["error"]) shouldBe "Vom Nutzer abgelehnt"
                }
                then("the read only says the request is over, and reading twice changes nothing") {
                    reads.forEach { it.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-qr-lookup", "step" to "closed") }
                }
                then("the WEB side's next poll fails with the rejection, the journey does not silently continue") {
                    templateOf(poll.stepData()["error"]) shouldBe "Vom Nutzer abgelehnt"
                }
            }
        }
    }
}
