package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.keycloak.PeerAuthAssertion
import com.example.identity.core.orchestrator.support.AccountFixtures
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

/** Covers the Keycloak facade's one facade-specific endpoint (docs/05-api.md Abschnitt 3b). */
class KeycloakChannelIntegrationTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var accountService: AccountService

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun stubAssertion(channelBinding: String) {
        every { peerAuthValidator.validate(any(), any(), any(), any()) } returns PeerAuthAssertion(
            jti = UUID.randomUUID().toString(),
            issuedAt = Instant.now(),
            channelBinding = channelBinding,
            subject = null
        )
    }

    private fun keycloakPatchRaw(channelSessionId: ChannelSessionId, body: String = "{}") =
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

    private fun keycloakPatch(channelSessionId: ChannelSessionId, body: String = "{}"): Map<String, Any?> =
        keycloakPatchRaw(channelSessionId, body).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    /** Same peer-auth-bearing headers, for the facade-neutral tool endpoints (docs/05-api.md Abschnitt 3b). */
    private fun keycloakHeaders(): HttpHeaders = HttpHeaders().apply {
        set("Authorization", "Bearer mock-peer-auth-token")
        set("Content-Type", "application/json")
    }

    private fun keycloakPost(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.POST, HttpEntity(body, keycloakHeaders()), mapType)
            .let { it.statusCode.is2xxSuccessful shouldBe true; it.body!! }

    private fun keycloakPatchTool(url: String, body: String): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, keycloakHeaders()), mapType)
            .let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    /** Runs auth-sms on a Web channel through to its end, as Keycloak's authenticator does. */
    private fun keycloakAuthSms(channelSessionId: ChannelSessionId): Map<String, Any?> {
        val (tan, activation) = captureMockTan { keycloakPost("/tools/api/auth-sms/v1?channel=$channelSessionId") }
        return keycloakPatchTool("/tools/api/auth-sms/v1/${activation.nextRaw()["toolSessionId"]}", """{"tan":"$tan"}""")
    }

    /** Runs auth-password on a Web channel through to its end, as Keycloak's authenticator does. */
    private fun keycloakAuthPassword(channelSessionId: ChannelSessionId): Map<String, Any?> {
        val toolSessionId = keycloakPost("/tools/api/auth-password/v1?channel=$channelSessionId").nextRaw()["toolSessionId"]
        return keycloakPatchTool("/tools/api/auth-password/v1/$toolSessionId", """{"password":"${AccountFixtures.DEMO_PASSWORD}"}""")
    }

    private fun accountWithSmsAndPassword(): AccountId =
        accountFixtures.seedAccount(methods = listOf(AccountFixtures.Method.Sms(), AccountFixtures.Method.Password()))

    /** The account a channel is bound to, read from its row. */
    private fun accountIdOf(channelSessionId: String): Long = jdbcTemplate.queryForObject(
        "SELECT account_id FROM orchestrator.channel_session WHERE id = ?",
        Long::class.java,
        UUID.fromString(channelSessionId)
    )!!

    init {
        given("a fresh Keycloak-chosen channelSessionId, no channel yet") {
            `when`("PATCH is called with the channel id as its binding (initial login)") {
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                val response = keycloakPatch(channelSessionId)

                then("it creates the channel and offers the initial-login candidates") {
                    response.channel()["channelSessionId"] shouldBe channelSessionId.toString()
                    response.channel()["channelType"] shouldBe "WEB"
                    response.next()["context"] shouldBe "auth"
                    response.next()["step"] shouldBe "selectMethod"
                    response["authData"] shouldBe mapOf<String, Any?>()
                }
            }

            `when`("PATCH is called twice with the same id and binding") {
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                val first = keycloakPatch(channelSessionId)
                val second = keycloakPatch(channelSessionId)

                then("the second call resumes the very same channel (idempotent upsert)") {
                    first.channel()["channelSessionId"] shouldBe channelSessionId.toString()
                    second.channel()["channelSessionId"] shouldBe channelSessionId.toString()
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orchestrator.channel_session", Int::class.java) shouldBe 1
                }
            }

            `when`("a later PATCH on the same id presents a different Keycloak binding") {
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                keycloakPatch(channelSessionId)
                stubAssertion(channelBinding = "a-completely-different-binding")
                val result = runCatching { keycloakPatchRaw(channelSessionId) }

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
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                val result = runCatching {
                    keycloakPatchRaw(channelSessionId, """{"subject":{"type":"account","id":"999999"}}""")
                }

                then("it is rejected up front as not found, never as an internal strategy error") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }
        }

        given("a step-up call naming an account Keycloak already knows") {
            `when`("PATCH is called with accountId and targetAcr") {
                val accountId = accountIdOf(loginAsSeededAccount())

                val keycloakChannelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = keycloakChannelSessionId.toString())
                val response = keycloakPatch(keycloakChannelSessionId, """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa2"}""")

                then("it binds the channel to that account and offers its auth candidates") {
                    response.next()["context"] shouldBe "auth"
                    response.next()["step"] shouldBe "selectMethod"
                    (response["authData"] as Map<*, *>)["subject"] shouldBe mapOf("type" to "account", "id" to accountId.toString())
                    @Suppress("UNCHECKED_CAST")
                    val options = response.stepData()["options"] as List<String>
                    // A known account gets auth candidates only, never identification
                    // (docs/05-api.md Abschnitt 3b).
                    options shouldNotContain "ident-fsc"
                    // Candidates are limited to this account's own active methods, not the whole catalog.
                    options shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
            }
        }

        given("a step-up call with a level the orchestrator does not know") {
            `when`("PATCH is called with targetAcr loa9") {
                val accountId = accountIdOf(loginAsSeededAccount())
                val keycloakChannelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = keycloakChannelSessionId.toString())

                val result = runCatching {
                    keycloakPatchRaw(keycloakChannelSessionId, """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa9"}""")
                }

                then("it is a 400, never a silent floor of none") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }
        }

        given("a Web channel already bound to one account") {
            `when`("a later PATCH on the same channel names a different account") {
                val accountId = accountIdOf(loginAsSeededAccount())
                val otherAccountId = accountService.createAccountInSetup().accountId

                val keycloakChannelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = keycloakChannelSessionId.toString())
                keycloakPatch(keycloakChannelSessionId, """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa2"}""")

                val result = runCatching { keycloakPatchRaw(keycloakChannelSessionId, """{"subject":{"type":"account","id":"$otherAccountId"}}""") }

                then("it is refused as a mismatch, never a silent rebind") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                    accountIdOf(keycloakChannelSessionId.toString()) shouldBe accountId
                }
            }
        }

        given("a Web channel offering auth-password-lookup as its initial-login candidate") {
            `when`("the facade-neutral tool endpoints are driven with peer-auth instead of DPoP") {
                // Seeded via the App channel (DPoP) - only the Keycloak-side calls below use peer-auth.
                val email = registerWithEmailAndPassword()

                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                val initial = keycloakPatch(channelSessionId)

                val toolSessionId = keycloakPost("/tools/api/auth-password-lookup/v1?channel=$channelSessionId")
                    .nextRaw()["toolSessionId"] as String
                val completed = keycloakPatchTool(
                    "/tools/api/auth-password-lookup/v1/$toolSessionId",
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
        }

        given("a Web step-up to loa2 whose account has no further method usable on the Web") {
            // Like an account registered in the app: the device works only there, and sms is
            // proven first. Nothing else of the account can close the gap here.
            `when`("the step-up starts, the user agrees to identify again and does so with the Freischaltcode") {
                val accountId = accountFixtures.seedAccount(
                    methods = listOf(AccountFixtures.Method.Sms(), AccountFixtures.Method.Device(thumbprint = "key-of-the-app"))
                )
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                keycloakPatch(channelSessionId, """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa2"}""")
                val started = keycloakAuthSms(channelSessionId)
                val offered = keycloakPost("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                val identToolSessionId = keycloakPost("/tools/api/ident-fsc/v1?channel=$channelSessionId")
                    .nextRaw()["toolSessionId"] as String
                val finished = keycloakPatchTool(
                    "/tools/api/ident-fsc/v1/$identToolSessionId",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
                )

                then("it first asks whether to identify again, instead of an empty selection") {
                    started.next()["step"] shouldBe "confirm"
                }
                then("after the answer it offers identification") {
                    @Suppress("UNCHECKED_CAST")
                    val options = offered.stepData()["options"] as List<String>
                    options shouldContainAll listOf("ident-fsc")
                }
                then("the identification closes the gap: authenticated at loa2") {
                    finished.channel()["state"] shouldBe "AUTHENTICATED"
                    (finished["authData"] as Map<*, *>)["acr"] shouldBe "loa2"
                }
            }
        }

        given("a fresh Web channel opened with intent=register") {
            `when`("PATCH is called with intent=register") {
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                val response = keycloakPatch(channelSessionId, """{"intent":"register"}""")

                then("it offers identification, never the login-only lookup candidates") {
                    // No account yet, so nothing is being set up (ADR-46).
                    response.channel()["state"] shouldBe "ANONYMOUS"
                    @Suppress("UNCHECKED_CAST")
                    val options = response.stepData()["options"] as List<String>
                    // shouldContainAll, not exact: identification is offered, not the catalog's exact set.
                    options shouldContainAll listOf("ident-fsc", "ident-eid")
                }
            }

            `when`("identification, sms enrollment, the email obligation and the factor-kind obligation are all driven through") {
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                keycloakPatch(channelSessionId, """{"intent":"register"}""")

                val identToolSessionId = keycloakPost("/tools/api/ident-fsc/v1?channel=$channelSessionId")
                    .nextRaw()["toolSessionId"] as String
                keycloakPatchTool(
                    "/tools/api/ident-fsc/v1/$identToolSessionId",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
                )

                val emailToolSessionId = keycloakPost("/tools/api/confirm-email/v1?channel=$channelSessionId")
                    .nextRaw()["toolSessionId"] as String
                val (emailCode, _) = captureMockTan {
                    keycloakPatchTool("/tools/api/confirm-email/v1/$emailToolSessionId", """{"email":"max@example.com"}""")
                }
                keycloakPatchTool("/tools/api/confirm-email/v1/$emailToolSessionId", """{"code":"$emailCode"}""")

                val smsToolSessionId = keycloakPost("/tools/api/enroll-sms/v1?channel=$channelSessionId")
                    .nextRaw()["toolSessionId"] as String
                val (smsTan, _) = captureMockTan {
                    keycloakPatchTool("/tools/api/enroll-sms/v1/$smsToolSessionId", """{"phoneNumber":"+49 170 1234567"}""")
                }
                keycloakPatchTool("/tools/api/enroll-sms/v1/$smsToolSessionId", """{"tan":"$smsTan"}""")

                val passwordToolSessionId = keycloakPost("/tools/api/enroll-password/v1?channel=$channelSessionId")
                    .nextRaw()["toolSessionId"] as String
                val finished = keycloakPatchTool(
                    "/tools/api/enroll-password/v1/$passwordToolSessionId",
                    """{"password":"correct-horse-battery"}"""
                )

                // The step order and the offered candidates are the same as on the App channel
                // (RegistrationFlowIntegrationTest); here only what the Web channel adds.
                then("the registration runs through the facade-neutral tool endpoints to AUTHENTICATED") {
                    finished.channel()["state"] shouldBe "AUTHENTICATED"
                }
                then("the finished run hands Keycloak the new account in authData") {
                    ((finished["authData"] as Map<*, *>)["subject"] as Map<*, *>)["type"] shouldBe "account"
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

        given("a Web login at loa2 whose proofs are restored in a later flow run (docs/04-orchestrierung.md #4)") {
            /** A login at loa2 on a fresh Web channel (password and sms), and the RestoreData its flow run ends with. */
            fun loginAtLoa2(accountId: AccountId): Pair<ChannelSessionId, String> {
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                keycloakPatch(channelSessionId, """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa2"}""")
                keycloakAuthPassword(channelSessionId)
                keycloakAuthSms(channelSessionId).channel()["state"] shouldBe "AUTHENTICATED"
                val token = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/kc/channels/$channelSessionId/restore-data?kcSessionId=kc-aged",
                    HttpMethod.GET, HttpEntity<Void>(keycloakHeaders()), mapType
                ).body!!["restoreData"] as String
                return channelSessionId to token
            }

            fun resume(accountId: AccountId, token: String): Map<String, Any?> {
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                return keycloakPatch(channelSessionId, """{"subject":{"type":"account","id":"$accountId"},"targetAcr":"loa2","restoreData":"$token","kcSessionId":"kc-aged"}""")
            }

            `when`("the next flow run asks for loa2 while the proofs are recent") {
                val accountId = accountWithSmsAndPassword()
                val (_, token) = loginAtLoa2(accountId)
                val resumed = resume(accountId, token)

                then("the restored proofs carry it") {
                    resumed.channel()["state"] shouldBe "AUTHENTICATED"
                    (resumed["authData"] as Map<*, *>)["acr"] shouldBe "loa2"
                }
            }

            `when`("the next flow run asks for loa2 after the proofs are older than 30 minutes") {
                val accountId = accountWithSmsAndPassword()
                val (first, _) = loginAtLoa2(accountId)
                ageProofs(first.value, minutes = 31)
                val token = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/kc/channels/$first/restore-data?kcSessionId=kc-aged",
                    HttpMethod.GET, HttpEntity<Void>(keycloakHeaders().also { stubAssertion(channelBinding = first.toString()) }), mapType
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
            fun expiresAt(channelSessionId: ChannelSessionId): Instant = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM orchestrator.channel_session WHERE id = ?", java.sql.Timestamp::class.java, channelSessionId.value
            )!!.toInstant()
            fun fetchRestoreData(channelSessionId: ChannelSessionId, sessionExpiresAt: Instant) = restTemplate.exchange(
                "http://localhost:$port/orchestrator/api/v1/kc/channels/$channelSessionId/restore-data" +
                    "?kcSessionId=kc-session-1&sessionExpiresAt=${sessionExpiresAt.epochSecond}",
                HttpMethod.GET, HttpEntity<Void>(keycloakHeaders()), mapType
            ).statusCode

            `when`("Keycloak's session ends before the channel's flow-run lifetime") {
                val channelSessionId = ChannelSessionId(UUID.randomUUID())
                stubAssertion(channelBinding = channelSessionId.toString())
                keycloakPatch(channelSessionId)
                val sessionEnd = Instant.now().plusSeconds(120)

                val status = fetchRestoreData(channelSessionId, sessionEnd)

                then("the channel's expiry is capped at the session's end") {
                    status shouldBe HttpStatus.OK
                    expiresAt(channelSessionId).epochSecond shouldBe sessionEnd.epochSecond
                }
            }
        }
    }
}
