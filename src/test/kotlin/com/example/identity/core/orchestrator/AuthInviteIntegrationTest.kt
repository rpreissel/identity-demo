package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.example.identity.core.orchestrator.kc.PeerAuthAssertion
import com.example.identity.core.orchestrator.kc.PeerAuthValidator
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.every
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Process access by one-time password on the Web channel (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md):
 * the channel signs in as the invitation, never as an account.
 */
class AuthInviteIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @Autowired
    private lateinit var clock: Clock

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun stubAssertion(channelAnchor: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns PeerAuthAssertion(
            jti = UUID.randomUUID().toString(), issuedAt = Instant.now(), channelAnchor = channelAnchor, subject = null
        )
    }

    private fun kcHeaders() = HttpHeaders().apply {
        set("Authorization", "Bearer mock-peer-auth-token")
        set("Content-Type", "application/json")
    }

    private fun kcCall(method: HttpMethod, url: String, body: String = "{}") =
        restTemplate.exchange("http://localhost:$port$url", method, HttpEntity(body, kcHeaders()), mapType)

    /** Opens a Web channel asking for [targetAcr] and activates auth-invite on it. */
    private fun openInviteTool(targetAcr: String): Pair<UUID, String> {
        val channelSessionId = UUID.randomUUID()
        stubAssertion(channelSessionId.toString())
        val initial = kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/kc/channels/$channelSessionId",
            withDefaultAvailableTools("""{"targetAcr":"$targetAcr"}""")).body!!
        @Suppress("UNCHECKED_CAST")
        (initial.stepData()["options"] as List<String>) shouldContain "auth-invite"
        val toolSessionId = kcCall(HttpMethod.POST, "/orchestrator/api/v1/channels/$channelSessionId/tools/auth-invite")
            .body!!.nextRaw()["toolSessionId"] as String
        return channelSessionId to toolSessionId
    }

    /** Issued by the register, as its page does; the answer is the letter with the plaintext. */
    private fun issue(personId: String, niveau: String): Letter {
        val validUntil = clock.instant().plus(Duration.ofDays(30))
        val letter = restTemplate.exchange(
            "http://localhost:$port/mock-personenverzeichnis/personen/$personId/einladungen", HttpMethod.POST,
            HttpEntity("""{"vorgang":"beitragsrueckerstattung","niveau":"$niveau","gueltigBis":"$validUntil"}""",
                HttpHeaders().apply { set("Content-Type", "application/json") }),
            mapType
        ).body!!
        return Letter(letter["einladungId"] as String, letter["code"] as String)
    }

    private data class Letter(val invitation: String, val code: String)

    private fun complete(invitation: String) {
        restTemplate.exchange("http://localhost:$port/mock-personenverzeichnis/einladungen/$invitation/abschluss",
            HttpMethod.POST, HttpEntity<Void>(HttpHeaders()), mapType)
    }

    init {
        Given("an open loa1 invitation for Max") {
            When("he enters his KVNR and the one-time password on a loa1 login") {
                Then("the channel is signed in as the invitation, with no account") {
                    val issued = issue("P000000001", "loa1")
                    val (channelSessionId, toolSessionId) = openInviteTool("loa1")

                    // Separators and case do not count.
                    val typed = issued.code.lowercase().replace("-", " ")
                    val completed = kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                        """{"kvnr":"A123456789","code":"$typed"}""").body!!

                    completed.channel()["state"] shouldBe "AUTHENTICATED"
                    val authData = completed["authData"] as Map<*, *>
                    authData["subject"] shouldBe mapOf("type" to "invitation", "id" to issued.invitation)
                    authData["accountId"] shouldBe null
                    authData["acr"] shouldBe "loa1"
                    authData["amr"] shouldBe mapOf("invite" to "orchestrator")
                    jdbcTemplate.queryForObject(
                        "SELECT invitation FROM orchestrator.channel_session WHERE id = ?", String::class.java, channelSessionId
                    ) shouldBe issued.invitation
                }
            }

            When("Keycloak ends the flow run and asks for restore data") {
                Then("it gets none: the invitation's evidence never reaches a later run") {
                    val issued = issue("P000000001", "loa1")
                    val (channelSessionId, toolSessionId) = openInviteTool("loa1")
                    kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                        """{"kvnr":"A123456789","code":"${issued.code}"}""")

                    val restore = kcCall(HttpMethod.GET,
                        "/orchestrator/api/v1/kc/channels/$channelSessionId/restore-data?kcSessionId=kc-session-1").body!!
                    restore["restoreData"] shouldBe null
                }
            }

            When("the login asks for loa2") {
                Then("the invitation is refused before anything is bound") {
                    val issued = issue("P000000001", "loa1")
                    val (channelSessionId, toolSessionId) = openInviteTool("loa2")

                    assertThrows<HttpClientErrorException> {
                        kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                            """{"kvnr":"A123456789","code":"${issued.code}"}""")
                    }
                    jdbcTemplate.queryForObject(
                        "SELECT invitation FROM orchestrator.channel_session WHERE id = ?", String::class.java, channelSessionId
                    ) shouldBe null
                }
            }

            When("someone else's number is entered with Max's password") {
                Then("it fails like a wrong password and charges that person's counter") {
                    val issued = issue("P000000001", "loa1")
                    val (_, toolSessionId) = openInviteTool("loa1")

                    val failed = kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                        """{"kvnr":"B987654321","code":"${issued.code}"}""")

                    failed.statusCode shouldBe HttpStatus.OK
                    failed.body!!.channel()["state"] shouldBe "ANONYMOUS"
                    jdbcTemplate.queryForObject(
                        "SELECT failed_count FROM orchestrator.attempt_throttle WHERE scope = 'PERSON' AND subject = ?", Int::class.java, "P000000002"
                    )!! shouldBeGreaterThanOrEqual 1
                }
            }
        }

        Given("a completed invitation") {
            Then("its password no longer signs in") {
                val issued = issue("P000000001", "loa1")
                complete(issued.invitation)
                val (_, toolSessionId) = openInviteTool("loa1")

                val failed = kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                    """{"kvnr":"A123456789","code":"${issued.code}"}""")

                failed.body!!.channel()["state"] shouldBe "ANONYMOUS"
            }
        }
    }
}
