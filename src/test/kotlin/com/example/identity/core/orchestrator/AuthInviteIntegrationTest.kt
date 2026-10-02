package com.example.identity.core.orchestrator

import com.example.identity.contract.texts.templateOf
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.example.identity.core.orchestrator.kc.PeerAuthAssertion
import com.example.identity.core.orchestrator.kc.PeerAuthValidator
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import io.kotest.assertions.throwables.shouldThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import com.example.identity.core.account.AccountService
import com.example.identity.core.account.SignInLog
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

    @Autowired
    private lateinit var accountService: AccountService

    @Autowired
    private lateinit var signInLog: SignInLog

    init {
        beforeScenario { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun stubAssertion(channelBinding: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns PeerAuthAssertion(
            jti = UUID.randomUUID().toString(), issuedAt = Instant.now(), channelBinding = channelBinding, subject = null
        )
    }

    private fun kcHeaders() = HttpHeaders().apply {
        set("Authorization", "Bearer mock-peer-auth-token")
        set("Content-Type", "application/json")
    }

    private fun kcCall(method: HttpMethod, url: String, body: String = "{}") =
        restTemplate.exchange("http://localhost:$port$url", method, HttpEntity(body, kcHeaders()), mapType)

    /** Opens a Web channel asking for [targetAcr] and activates auth-invite on it. */
    private fun openInviteTool(targetAcr: String): Pair<ChannelSessionId, String> {
        val channelSessionId = ChannelSessionId(UUID.randomUUID())
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
    private fun issue(personId: PartnerNumber, niveau: String): Letter {
        val validUntil = clock.instant().plus(Duration.ofDays(30))
        val letter = restTemplate.exchange(
            "http://localhost:$port/mock-personenverzeichnis/personen/$personId/einladungen", HttpMethod.POST,
            HttpEntity("""{"vorgang":"beitragsrueckerstattung","niveau":"$niveau","gueltigBis":"$validUntil"}""",
                HttpHeaders().apply { set("Content-Type", "application/json") }),
            mapType
        ).body!!
        return Letter(InvitationId(letter["einladungId"] as String), letter["code"] as String)
    }

    private data class Letter(val invitation: InvitationId, val code: String)

    /** Where a failed attempt leaves the client: the tool's one step, to try again. */
    private val stillOnInvite = mapOf("type" to "tool", "toolId" to "auth-invite", "step" to "auth")

    private fun complete(invitation: InvitationId) {
        restTemplate.exchange("http://localhost:$port/mock-personenverzeichnis/einladungen/$invitation/abschluss",
            HttpMethod.POST, HttpEntity<Void>(HttpHeaders()), mapType)
    }

    init {
        given("a Web channel signed in as an invitation (I-5)") {
            fun signedInAsInvitation(): Pair<ChannelSessionId, InvitationId> {
                val issued = issue(PartnerNumber("P000000001"), "loa1")
                val (channelSessionId, toolSessionId) = openInviteTool("loa1")
                kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                    """{"kvnr":"A123456789","code":"${issued.code}"}""")
                return channelSessionId to issued.invitation
            }
            fun upsert(channelSessionId: ChannelSessionId, body: String) = runCatching {
                stubAssertion(channelSessionId.toString())
                kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/kc/channels/$channelSessionId", body)
            }
            fun invitationOf(channelSessionId: ChannelSessionId) = jdbcTemplate.queryForObject(
                "SELECT invitation FROM orchestrator.channel_session WHERE id = ?", String::class.java, channelSessionId.value
            )?.let(::InvitationId)
            fun accountOf(channelSessionId: ChannelSessionId) = jdbcTemplate.queryForObject(
                "SELECT account_id FROM orchestrator.channel_session WHERE id = ?", Long::class.javaObjectType, channelSessionId.value
            )

            `when`("Keycloak names an account for it") {
                val (channelSessionId, invitation) = signedInAsInvitation()
                val accountId = accountService.createAccountInSetup().accountId
                val result = upsert(channelSessionId, """{"subject":{"type":"account","id":"$accountId"}}""")

                then("it is refused as a mismatch, and the channel stays the invitation's") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                    invitationOf(channelSessionId) shouldBe invitation
                    accountOf(channelSessionId) shouldBe null
                }
            }

            `when`("Keycloak names another invitation for it") {
                val (channelSessionId, invitation) = signedInAsInvitation()
                val result = upsert(channelSessionId, """{"subject":{"type":"invitation","id":"another-invitation"}}""")

                then("it is refused as a mismatch") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                    invitationOf(channelSessionId) shouldBe invitation
                }
            }

            `when`("a later flow run of that session asks the orchestrator for a step-up (K-5)") {
                val (_, invitation) = signedInAsInvitation()
                val freshChannel = ChannelSessionId(UUID.randomUUID())
                val result = upsert(freshChannel, """{"subject":{"type":"invitation","id":"$invitation"},"targetAcr":"loa2"}""")
                val created = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orchestrator.channel_session WHERE id = ?", Int::class.java, freshChannel.value)

                then("it is refused with the reason, and no channel is opened: a process access is not raised") {
                    val refused = shouldThrow<HttpClientErrorException> { result.getOrThrow() }
                    refused.statusCode shouldBe HttpStatus.CONFLICT
                    refused.responseBodyAsString shouldContain "INVALID_STATE_TRANSITION"
                    created shouldBe 0
                }
            }

            `when`("Keycloak names the invitation for a channel nobody signed in on yet") {
                val (_, invitation) = signedInAsInvitation()
                val anonymous = ChannelSessionId(UUID.randomUUID())
                upsert(anonymous, "{}").getOrThrow()
                val result = upsert(anonymous, """{"subject":{"type":"invitation","id":"$invitation"}}""")

                then("it is refused as well: only its own proof binds an invitation") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                    invitationOf(anonymous) shouldBe null
                }
            }

            `when`("Keycloak names the same invitation") {
                val (channelSessionId, invitation) = signedInAsInvitation()
                val result = upsert(channelSessionId, """{"subject":{"type":"invitation","id":"$invitation"}}""")

                then("the channel resumes as it was") {
                    result.getOrThrow().statusCode shouldBe HttpStatus.OK
                    invitationOf(channelSessionId) shouldBe invitation
                }
            }
        }

        given("a Web channel signed in as an invitation, whose Keycloak session ends (A-1, ADR-48)") {
            `when`("Keycloak reports the logout of that session") {
                val issued = issue(PartnerNumber("P000000001"), "loa1")
                val (channelSessionId, toolSessionId) = openInviteTool("loa1")
                kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                    """{"kvnr":"A123456789","code":"${issued.code}"}""")
                // The end of the flow run tells the channel its durable Keycloak session.
                kcCall(HttpMethod.GET, "/orchestrator/api/v1/kc/channels/$channelSessionId/restore-data?kcSessionId=kc-invite-session")
                stubAssertion(issued.invitation.value)
                val reported = kcCall(HttpMethod.POST,
                    "/orchestrator/api/v1/kc/invitations/${issued.invitation}/sign-outs?kcSessionId=kc-invite-session")
                val state = jdbcTemplate.queryForObject(
                    "SELECT state FROM orchestrator.channel_session WHERE id = ?", String::class.java, channelSessionId.value)
                val log = signInLog.ofInvitation(issued.invitation)

                then("the channel ends with it") {
                    reported.statusCode shouldBe HttpStatus.NO_CONTENT
                    state shouldBe "LOGGED_OUT"
                }

                then("the sign-in log holds the sign-in and the sign-out of the invitation") {
                    log.map { it.signInType } shouldBe listOf("SIGNED_IN", "SIGNED_OUT")
                    log.first().acr shouldBe "loa1"
                }
            }

            `when`("the report names another invitation than the assertion") {
                val issued = issue(PartnerNumber("P000000001"), "loa1")
                stubAssertion("another-invitation")
                val result = runCatching {
                    kcCall(HttpMethod.POST, "/orchestrator/api/v1/kc/invitations/${issued.invitation}/sign-outs?kcSessionId=kc-invite-session")
                }

                then("it is refused") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.UNAUTHORIZED
                }
            }
        }

        given("an open loa1 invitation for Max") {
            `when`("he enters his KVNR and the one-time password on a loa1 login") {
                val issued = issue(PartnerNumber("P000000001"), "loa1")
                val (channelSessionId, toolSessionId) = openInviteTool("loa1")

                // Separators and case do not count.
                val typed = issued.code.lowercase().replace("-", " ")
                val completed = kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                    """{"kvnr":"A123456789","code":"$typed"}""").body!!

                then("the channel is signed in as the invitation, with no account") {
                    completed.channel()["state"] shouldBe "AUTHENTICATED"
                    val authData = completed["authData"] as Map<*, *>
                    authData["subject"] shouldBe mapOf("type" to "invitation", "id" to issued.invitation.value)
                    authData["acr"] shouldBe "loa1"
                    authData["amr"] shouldBe mapOf("invite" to "orchestrator")
                    jdbcTemplate.queryForObject(
                        "SELECT invitation FROM orchestrator.channel_session WHERE id = ?", String::class.java, channelSessionId.value
                    ) shouldBe issued.invitation.value
                }
            }

            `when`("Keycloak ends the flow run and asks for restore data") {
                val issued = issue(PartnerNumber("P000000001"), "loa1")
                val (channelSessionId, toolSessionId) = openInviteTool("loa1")
                kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                    """{"kvnr":"A123456789","code":"${issued.code}"}""")

                val restore = kcCall(HttpMethod.GET,
                    "/orchestrator/api/v1/kc/channels/$channelSessionId/restore-data?kcSessionId=kc-session-1").body!!

                then("it gets none: the invitation's evidence never reaches a later run") {
                    restore["restoreData"] shouldBe null
                }
            }

            `when`("the login asks for loa2") {
                val issued = issue(PartnerNumber("P000000001"), "loa1")
                val (channelSessionId, toolSessionId) = openInviteTool("loa2")

                val result = runCatching {
                    kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                        """{"kvnr":"A123456789","code":"${issued.code}"}""")
                }

                then("the invitation is refused before anything is bound") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }
                    jdbcTemplate.queryForObject(
                        "SELECT invitation FROM orchestrator.channel_session WHERE id = ?", String::class.java, channelSessionId.value
                    ) shouldBe null
                }
            }

            `when`("someone else's number is entered with Max's password") {
                val issued = issue(PartnerNumber("P000000001"), "loa1")
                val (_, toolSessionId) = openInviteTool("loa1")

                val failed = kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                    """{"kvnr":"B987654321","code":"${issued.code}"}""")

                then("it fails like a wrong password and charges that person's counter") {
                    failed.statusCode shouldBe HttpStatus.OK
                    failed.body!!.channel()["state"] shouldBe "ANONYMOUS"
                    jdbcTemplate.queryForObject(
                        "SELECT failed_count FROM orchestrator.rate_limit WHERE scope = 'PERSON' AND subject = ?", Int::class.java, "P000000002"
                    ) shouldBe 1
                }
            }
        }

        given("a completed invitation") {
            `when`("Max enters its password again") {
                val issued = issue(PartnerNumber("P000000001"), "loa1")
                complete(issued.invitation)
                val (_, toolSessionId) = openInviteTool("loa1")

                val failed = kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                    """{"kvnr":"A123456789","code":"${issued.code}"}""")

                then("its password no longer signs in: the step stays, with an error") {
                    failed.body!!.channel()["state"] shouldBe "ANONYMOUS"
                    failed.body!!.next() shouldBe stillOnInvite
                    templateOf(failed.body!!.stepData()["error"]) shouldBe "Nummer oder Einmalkennwort ungueltig"
                }
            }
        }

        given("an open loa2 invitation for Paula, known by her Partnernummer only") {
            /** Signs in on a fresh loa2 Web channel with her Partnernummer and returns the channel. */
            fun signedInAsPaula(): ChannelSessionId {
                val issued = issue(PartnerNumber("P000000004"), "loa2")
                val (channelSessionId, toolSessionId) = openInviteTool("loa2")
                kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                    """{"partnerNumber":"P000000004","code":"${issued.code}"}""")
                return channelSessionId
            }

            `when`("she enters the Partnernummer and the password") {
                val issued = issue(PartnerNumber("P000000004"), "loa2")
                val (_, toolSessionId) = openInviteTool("loa2")

                val completed = kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                    """{"partnerNumber":"P000000004","code":"${issued.code}"}""").body!!

                then("the channel is signed in at the invitation's level") {
                    completed.channel()["state"] shouldBe "AUTHENTICATED"
                    (completed["authData"] as Map<*, *>)["acr"] shouldBe "loa2"
                }
            }

            // A process access serves its process and nothing else.
            `when`("the signed-in channel asks to manage methods") {
                val channelSessionId = signedInAsPaula()

                val enrollment = runCatching {
                    kcCall(HttpMethod.POST, "/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                }

                then("it is refused") {
                    shouldThrow<HttpClientErrorException> { enrollment.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
            }

            `when`("the signed-in channel asks to delete an account") {
                val channelSessionId = signedInAsPaula()

                val deletion = runCatching {
                    kcCall(HttpMethod.POST, "/orchestrator/api/v1/channels/$channelSessionId/account-deletions")
                }

                then("it is refused") {
                    shouldThrow<HttpClientErrorException> { deletion.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
            }
        }

        given("an invitation the register revoked") {
            `when`("Max enters its password") {
                val issued = issue(PartnerNumber("P000000001"), "loa1")
                val revoked = restTemplate.exchange("http://localhost:$port/mock-personenverzeichnis/einladungen/${issued.invitation}",
                    HttpMethod.DELETE, HttpEntity<Void>(HttpHeaders()), Void::class.java).statusCode
                val (_, toolSessionId) = openInviteTool("loa1")

                val failed = kcCall(HttpMethod.PATCH, "/orchestrator/api/v1/tools/$toolSessionId/auth-invite",
                    """{"kvnr":"A123456789","code":"${issued.code}"}""").body!!

                then("a revoked password no longer signs in: the step stays, with an error") {
                    revoked shouldBe HttpStatus.NO_CONTENT
                    failed.channel()["state"] shouldBe "ANONYMOUS"
                    failed.next() shouldBe stillOnInvite
                    templateOf(failed.stepData()["error"]) shouldBe "Nummer oder Einmalkennwort ungueltig"
                }
            }
        }

        given("an App that announces auth-invite anyway") {
            `when`("a right one-time password is entered there") {
                val issued = issue(PartnerNumber("P000000001"), "loa1")
                val start = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val appChannel = start.channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/channels/$appChannel/tools/auth-invite").nextRaw()["toolSessionId"] as String

                val result = runCatching {
                    patch("/orchestrator/api/v1/tools/$toolSessionId/auth-invite", """{"kvnr":"A123456789","code":"${issued.code}"}""")
                }

                then("it is refused, not bound: process access is for the website only") {
                    val refused = shouldThrow<HttpClientErrorException> { result.getOrThrow() }
                    refused.statusCode.is4xxClientError shouldBe true
                    refused.responseBodyAsString shouldContain "PROCESS_ABORTED"
                    jdbcTemplate.queryForObject(
                        "SELECT invitation FROM orchestrator.channel_session WHERE id = ?", String::class.java, UUID.fromString(appChannel)
                    ) shouldBe null
                }
            }
        }
    }
}
