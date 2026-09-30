package com.example.identity.core.orchestrator

import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.example.identity.core.orchestrator.kc.PeerAuthAssertion
import com.example.identity.core.orchestrator.kc.PeerAuthValidator
import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import java.time.Instant
import java.util.UUID
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/** Covers the kc-facade's one facade-specific endpoint (docs/05-api.md Abschnitt 3). */
class KcChannelIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @Autowired
    private lateinit var accountService: AccountService

    override val resetPerWhen = true

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

    private fun kcPatchRaw(channelSessionId: UUID, body: String = "{}") =
        restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/kc/channels/$channelSessionId",
            HttpMethod.PATCH,
            HttpEntity(
                withDefaultAvailableTools(body),
                HttpHeaders().apply {
                    set("Authorization", "Bearer mock-peer-auth-token")
                    set("Content-Type", "application/json")
                }
            ),
            mapType
        )

    private fun kcPatch(channelSessionId: UUID, body: String = "{}"): Map<String, Any?> =
        kcPatchRaw(channelSessionId, body).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    /** Same peer-auth-bearing headers, for the facade-neutral tool endpoints (docs/05-api.md Abschnitt 3). */
    private fun kcHeaders(): HttpHeaders = HttpHeaders().apply {
        set("Authorization", "Bearer mock-peer-auth-token")
        set("Content-Type", "application/json")
    }

    private fun kcPost(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.POST, HttpEntity(body, kcHeaders()), mapType)
            .let { it.statusCode.is2xxSuccessful shouldBe true; it.body!! }

    private fun kcPatchTool(url: String, body: String): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, kcHeaders()), mapType)
            .let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    init {
        given("a fresh Keycloak-chosen channelSessionId, no channel yet") {
            `when`("PATCH is called with the channel id as its binding (initial login)") {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                val response = kcPatch(channelSessionId)

                then("it creates the channel and offers the initial-login candidates") {
                    response.channel()["channelSessionId"] shouldBe channelSessionId.toString()
                    response.channel()["channelType"] shouldBe "WEB"
                    response.next()["context"] shouldBe "auth"
                    response.next()["step"] shouldBe "selectMethod"
                    response["authData"] shouldBe mapOf<String, Any?>()
                }
            }

            `when`("PATCH is called twice with the same id and binding") {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                val first = kcPatch(channelSessionId)
                val second = kcPatch(channelSessionId)

                then("the second call resumes the very same channel (idempotent upsert)") {
                    first.channel()["channelSessionId"] shouldBe second.channel()["channelSessionId"]
                }
            }

            `when`("a later PATCH on the same id presents a different kc binding") {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                kcPatch(channelSessionId)
                stubAssertion(channelBinding = "a-completely-different-binding")
                val result = runCatching { kcPatchRaw(channelSessionId) }

                then("it is rejected as a binding mismatch") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }

            `when`("the Authorization header is missing") {
                val result = runCatching {
                    restTemplate.exchange(
                        "http://localhost:$port/orchestrator/api/v1/kc/channels/${UUID.randomUUID()}",
                        HttpMethod.PATCH,
                        HttpEntity("{}", HttpHeaders().apply { set("Content-Type", "application/json") }),
                        mapType
                    )
                }

                then("it is rejected as unauthorized") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.UNAUTHORIZED
                }
            }

            `when`("accountId names an account that doesn't exist") {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                val result = runCatching {
                    kcPatchRaw(channelSessionId, """{"subject":{"type":"account","id":"999999"},"amr":[{"nativeToolId":"kc-sms-form","amrSourceId":"kc-sms-form-exec-1"}]}""")
                }

                then("it is rejected up front as not found, never as an internal strategy error") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }
        }

        given("a step-up call naming an account Keycloak already knows") {
            `when`("PATCH is called with accountId and targetAcr") {
                val authenticatedChannelSessionId = loginAsSeededAccount()
                val accountId = jdbcTemplate.queryForObject(
                    "SELECT account_id FROM orchestrator.channel_session WHERE id = ?",
                    Long::class.java,
                    UUID.fromString(authenticatedChannelSessionId)
                )

                val kcChannelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = kcChannelSessionId.toString())
                val response = kcPatch(kcChannelSessionId, """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa2"}""")

                then("it binds the channel to that account and offers its auth candidates") {
                    response.next()["context"] shouldBe "auth"
                    response.next()["step"] shouldBe "selectMethod"
                    (response["authData"] as Map<*, *>)["subject"] shouldBe mapOf("type" to "account", "id" to accountId.toString())
                    @Suppress("UNCHECKED_CAST")
                    val options = response.stepData()["options"] as List<String>
                    // A known account gets auth candidates only, never identification
                    // (docs/05-api.md Abschnitt 3).
                    options shouldNotContain "ident-fsc"
                    // Candidates are limited to this account's own active methods, not the whole catalog.
                    options shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
            }
        }

        given("a kc channel already bound to one account") {
            `when`("a later PATCH on the same channel names a different account") {
                val authenticatedChannelSessionId = loginAsSeededAccount()
                val accountId = jdbcTemplate.queryForObject(
                    "SELECT account_id FROM orchestrator.channel_session WHERE id = ?",
                    Long::class.java,
                    UUID.fromString(authenticatedChannelSessionId)
                )
                val otherAccountId = accountService.createAccountInSetup().accountId

                val kcChannelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = kcChannelSessionId.toString())
                kcPatch(kcChannelSessionId, """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa2"}""")

                val result = runCatching { kcPatchRaw(kcChannelSessionId, """{"subject":{"type":"account","id":"$otherAccountId"}}""") }

                then("it is refused as a mismatch, never a silent rebind") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                    jdbcTemplate.queryForObject(
                        "SELECT account_id FROM orchestrator.channel_session WHERE id = ?",
                        Long::class.java,
                        kcChannelSessionId
                    ) shouldBe accountId
                }
            }
        }

        given("a kc channel offering auth-password-lookup as its initial-login candidate") {
            `when`("the facade-neutral tool endpoints are driven with peer-auth instead of DPoP") {
                // Seeded via the App channel (DPoP) - only the kc-side calls below use peer-auth.
                val email = registerWithEmailAndPassword()

                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                val initial = kcPatch(channelSessionId)

                val toolSessionId = kcPost("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup")
                    .nextRaw()["toolSessionId"] as String
                val completed = kcPatchTool(
                    "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                    """{"email":"$email","password":"correct-horse-battery"}"""
                )

                then("lookup-based login completes through them exactly like the App channel's own flow") {
                    @Suppress("UNCHECKED_CAST")
                    (initial.stepData()["options"] as List<String>) shouldNotContain "ident-fsc"
                    completed.channel()["channelSessionId"] shouldBe channelSessionId.toString()
                    ((completed["authData"] as Map<*, *>)["subject"] as Map<*, *>)["type"] shouldBe "account"
                    // A completed orchestrator tool tags its amr with "orchestrator", never "kc".
                    @Suppress("UNCHECKED_CAST")
                    val amr = (completed["authData"] as Map<String, Any?>)["amr"] as Map<String, String>
                    amr shouldBe mapOf("password" to "orchestrator")
                }
            }

            `when`("a later PATCH re-reports the same method via amr (a naive full-list resend)") {
                val email = registerWithEmailAndPassword()
                val channelSessionId = UUID.randomUUID()
                val binding = channelSessionId.toString()
                stubAssertion(channelBinding = binding)
                kcPatch(channelSessionId)
                val toolSessionId = kcPost("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup")
                    .nextRaw()["toolSessionId"] as String
                kcPatchTool(
                    "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                    """{"email":"$email","password":"correct-horse-battery"}"""
                )

                // Same channel and binding: Keycloak resends "password" as if it were native evidence.
                stubAssertion(channelBinding = binding)
                val resumed = kcPatch(channelSessionId, """{"amr":[{"nativeToolId":"kc-password-form","amrSourceId":"kc-password-form-exec-1"}]}""")

                then("its source stays orchestrator - the stronger, verified claim is never downgraded to kc") {
                    @Suppress("UNCHECKED_CAST")
                    val amr = (resumed["authData"] as Map<String, Any?>)["amr"] as Map<String, String>
                    amr shouldBe mapOf("password" to "orchestrator")
                }
            }
        }

        given("a step-up channel whose account already reaches loa1 evidence natively") {
            `when`("PATCH is called again with amr (simulating a native Keycloak authenticator)") {
                val authenticatedChannelSessionId = loginAsSeededAccount()
                val accountId = jdbcTemplate.queryForObject(
                    "SELECT account_id FROM orchestrator.channel_session WHERE id = ?",
                    Long::class.java,
                    UUID.fromString(authenticatedChannelSessionId)
                )

                val kcChannelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = kcChannelSessionId.toString())
                // sms alone (loa1) does not reach the loa2 floor, but authData already shows the
                // native evidence.
                val partial = kcPatch(
                    kcChannelSessionId,
                    """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa2","amr":[{"nativeToolId":"kc-sms-form","amrSourceId":"kc-sms-form-exec-1"}]}"""
                )
                // A second native factor type (password, KNOWLEDGE) reaches loa2 like two
                // orchestrator factors would. `amr` is the complete currently valid kc set
                // (docs/05-api.md Abschnitt 3), so "sms" is resent. Omitting it would mean it expired.
                val authenticated = kcPatch(
                    kcChannelSessionId,
                    """{"subject":{"type":"account","id":"$accountId"},"amr":[{"nativeToolId":"kc-sms-form","amrSourceId":"kc-sms-form-exec-1"},{"nativeToolId":"kc-password-form","amrSourceId":"kc-password-form-exec-1"}]}"""
                )

                then("the merged evidence is reflected in authData and, once sufficient, authenticates") {
                    (partial["authData"] as Map<*, *>)["acr"] shouldBe "loa1"
                    // amr maps method -> source (docs/05-api.md Abschnitt 3). "sms" came from a native
                    // authenticator, not a tool.
                    @Suppress("UNCHECKED_CAST")
                    val partialAmr = (partial["authData"] as Map<String, Any?>)["amr"] as Map<String, String>
                    partialAmr shouldBe mapOf("sms" to "kc")

                    authenticated.channel()["state"] shouldBe "AUTHENTICATED"
                    @Suppress("UNCHECKED_CAST")
                    val finalAmr = (authenticated["authData"] as Map<String, Any?>)["amr"] as Map<String, String>
                    finalAmr shouldBe mapOf("sms" to "kc", "password" to "kc")
                }
            }
        }

        given("a fresh kc channel opened with intent=register") {
            `when`("PATCH is called with intent=register") {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                val response = kcPatch(channelSessionId, """{"intent":"register"}""")

                then("it offers identification, never the login-only lookup candidates") {
                    // No account yet, so nothing is being set up (ADR-46).
                    response.channel()["state"] shouldBe "ANONYMOUS"
                    @Suppress("UNCHECKED_CAST")
                    val options = response.stepData()["options"] as List<String>
                    // shouldContainAll, not exact: identification is offered, not the catalog's exact set.
                    options shouldContainAll listOf("ident-fsc", "ident-eid")
                }
            }

            `when`("an unknown intent is named") {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                val result = runCatching {
                    kcPatchRaw(channelSessionId, withDefaultAvailableTools("""{"intent":"lookup_login"}"""))
                }

                then("it is rejected up front, never silently mapped to web_select_method") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
            }

            `when`("identification, sms enrollment, the email obligation and the factor-kind obligation are all driven through") {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                kcPatch(channelSessionId, """{"intent":"register"}""")

                val identToolSessionId = kcPost("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc")
                    .nextRaw()["toolSessionId"] as String
                val afterIdent = kcPatchTool(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
                )

                val emailToolSessionId = kcPost("/orchestrator/api/v1/channels/$channelSessionId/tools/confirm-email")
                    .nextRaw()["toolSessionId"] as String
                val (emailCode, _) = captureMockTan {
                    kcPatchTool("/orchestrator/api/v1/tools/$emailToolSessionId/confirm-email", """{"email":"max@example.com"}""")
                }
                val afterEmail = kcPatchTool("/orchestrator/api/v1/tools/$emailToolSessionId/confirm-email", """{"code":"$emailCode"}""")

                val smsToolSessionId = kcPost("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms")
                    .nextRaw()["toolSessionId"] as String
                val (smsTan, _) = captureMockTan {
                    kcPatchTool("/orchestrator/api/v1/tools/$smsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val afterSms = kcPatchTool("/orchestrator/api/v1/tools/$smsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")

                val passwordToolSessionId = kcPost("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password")
                    .nextRaw()["toolSessionId"] as String
                val finished = kcPatchTool(
                    "/orchestrator/api/v1/tools/$passwordToolSessionId/enroll-password",
                    """{"password":"correct-horse-battery"}"""
                )

                then("the channel ends up AUTHENTICATED, email confirmed and enroll-password last") {
                    // The address comes before any method, since it unlocks enroll-password
                    // (docs/03-tool-architektur.md #1). Single candidate, so the selection page is
                    // skipped (docs/04-orchestrierung.md #4).
                    afterIdent.nextRaw()["toolId"] shouldBe "confirm-email"

                    // With the address confirmed, enroll-password is offered. shouldContainAll, not
                    // exact: new catalog methods don't change this.
                    @Suppress("UNCHECKED_CAST")
                    val afterEmailOptions = afterEmail.stepData()["options"] as List<String>
                    afterEmailOptions shouldContainAll listOf("enroll-sms", "enroll-device", "enroll-qr", "enroll-password")

                    // sms reaches the loa1 floor and the address is confirmed. Only the obligation to
                    // add a second factor kind is left. This test declares every tool, so the choice
                    // holds more than the Web theme's password.
                    @Suppress("UNCHECKED_CAST")
                    val afterSmsOptions = afterSms.stepData()["options"] as List<String>
                    afterSmsOptions shouldContainAll listOf("enroll-password", "enroll-device")
                    afterSmsOptions shouldNotContain "enroll-sms"
                    // sms makes the account set up (ADR-46); the obligation left is the journey's, not the account's.
                    afterSms.channel()["state"] shouldBe "ANONYMOUS"

                    // Authenticated only after all three obligations: method, confirmed email, second factor kind.
                    finished.channel()["state"] shouldBe "AUTHENTICATED"
                }
            }
        }

        given("an authenticated App channel") {
            `when`("the channel is resumed") {
                val channelSessionId = loginAsSeededAccount()
                val resumed = get("/orchestrator/api/v1/channels/$channelSessionId")

                then("its response never carries authData - authData is WEB-only") {
                    resumed.containsKey("authData") shouldBe false
                }
            }
        }

        given("a Web login at loa2 whose proofs are restored in a later flow run (docs/04-orchestrierung.md #8)") {
            val natives = """[{"nativeToolId":"kc-password-form","amrSourceId":"pw-1"},{"nativeToolId":"kc-otp-form","amrSourceId":"otp-1"}]"""

            /** A login at loa2 on a fresh kc channel, and the RestoreData its flow run ends with. */
            fun loginAtLoa2(accountId: Long): Pair<UUID, String> {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                kcPatch(channelSessionId, """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa2","amr":$natives}""")
                    .channel()["state"] shouldBe "AUTHENTICATED"
                val token = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/kc/channels/$channelSessionId/restore-data?kcSessionId=kc-aged",
                    HttpMethod.GET, HttpEntity<Void>(kcHeaders()), mapType
                ).body!!["restoreData"] as String
                return channelSessionId to token
            }

            /** Moves every proof of [channelSessionId] back by [minutes], as if made that long ago. */
            fun ageProofs(channelSessionId: UUID, minutes: Long) {
                val evidenceId = jdbcTemplate.queryForObject(
                    "SELECT session_evidence_id FROM orchestrator.channel_session WHERE id = ?", UUID::class.java, channelSessionId
                )
                val json = jdbcTemplate.queryForObject(
                    "SELECT CAST(methods AS VARCHAR) FROM orchestrator.session_evidence WHERE id = ?", String::class.java, evidenceId
                )!!
                val then = Instant.now().minusSeconds(minutes * 60)
                val aged = Regex("\"provenAt\":(\"[^\"]*\"|[0-9.eE+-]+)").replace(json) { match ->
                    val stamp = if (match.groupValues[1].startsWith("\"")) "\"$then\"" else "${then.epochSecond}.${"%09d".format(then.nano)}"
                    "\"provenAt\":$stamp"
                }
                aged shouldNotBe json
                jdbcTemplate.update("UPDATE orchestrator.session_evidence SET methods = ? FORMAT JSON WHERE id = ?", aged, evidenceId)
            }

            fun resume(accountId: Long, token: String): Map<String, Any?> {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                return kcPatch(channelSessionId, """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa2","restoreData":"$token","kcSessionId":"kc-aged"}""")
            }

            `when`("the next flow run asks for loa2 while the proofs are recent") {
                val accountId = accountService.createAccountInSetup().accountId
                val (_, token) = loginAtLoa2(accountId)
                val resumed = resume(accountId, token)

                then("the restored proofs carry it") {
                    resumed.channel()["state"] shouldBe "AUTHENTICATED"
                    (resumed["authData"] as Map<*, *>)["acr"] shouldBe "loa2"
                }
            }

            `when`("the next flow run asks for loa2 after the proofs are older than 30 minutes") {
                val accountId = accountService.createAccountInSetup().accountId
                val (first, _) = loginAtLoa2(accountId)
                ageProofs(first, minutes = 31)
                val token = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/kc/channels/$first/restore-data?kcSessionId=kc-aged",
                    HttpMethod.GET, HttpEntity<Void>(kcHeaders().also { stubAssertion(channelBinding = first.toString()) }), mapType
                ).body!!["restoreData"] as String
                val resumed = resume(accountId, token)

                then("they carry only loa1, and a new proof is asked for") {
                    resumed.channel()["state"] shouldNotBe "AUTHENTICATED"
                    resumed.next()["step"] shouldBe "selectMethod"
                    (resumed["authData"] as Map<*, *>)["acr"] shouldBe "loa1"
                }
            }
        }

        given("a Web channel whose flow run ends (ADR-43)") {
            fun expiresAt(channelSessionId: UUID): Instant = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM orchestrator.channel_session WHERE id = ?", java.sql.Timestamp::class.java, channelSessionId
            )!!.toInstant()
            fun fetchRestoreData(channelSessionId: UUID, sessionExpiresAt: Instant) = restTemplate.exchange(
                "http://localhost:$port/orchestrator/api/v1/kc/channels/$channelSessionId/restore-data" +
                    "?kcSessionId=kc-session-1&sessionExpiresAt=${sessionExpiresAt.epochSecond}",
                HttpMethod.GET, HttpEntity<Void>(kcHeaders()), mapType
            ).statusCode

            `when`("Keycloak's session ends before the channel's flow-run lifetime") {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                kcPatch(channelSessionId)
                val sessionEnd = Instant.now().plusSeconds(120)

                val status = fetchRestoreData(channelSessionId, sessionEnd)

                then("the channel's expiry is capped at the session's end") {
                    status shouldBe HttpStatus.OK
                    expiresAt(channelSessionId).epochSecond shouldBe sessionEnd.epochSecond
                }
            }

            `when`("Keycloak's session outlasts the flow-run lifetime") {
                val channelSessionId = UUID.randomUUID()
                stubAssertion(channelBinding = channelSessionId.toString())
                kcPatch(channelSessionId)
                val before = expiresAt(channelSessionId)

                val status = fetchRestoreData(channelSessionId, Instant.now().plusSeconds(10 * 3600))

                then("the channel keeps its shorter lifetime") {
                    status shouldBe HttpStatus.OK
                    expiresAt(channelSessionId) shouldBe before
                }
            }
        }
    }
}
