package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.ToolId
import io.kotest.assertions.withClue
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.simulation.mail.MailServer
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.core.orchestrator.admin.ADMIN_API
import com.example.identity.core.orchestrator.dpop.DpopProof
import com.example.identity.core.orchestrator.support.AccountFixtures
import com.example.identity.core.orchestrator.support.ContractStatusCheck
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.nimbusds.jose.jwk.JWK
import io.mockk.every
import io.mockk.mockk
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.client.RestTemplate
import io.kotest.core.names.TestName
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestType
import java.time.Instant
import java.util.UUID

/**
 * Shared HTTP client, DB reset and flow helpers for the orchestrator integration tests.
 *
 * Each subclass stubs the [dpopValidator] spy in its own `beforeScenario`. Most use
 * [stubDpopWithFakeJwk]; the flow helpers below assume that stub. Device-binding suites use a real
 * EC key instead.
 */
abstract class IntegrationTestSupport : SharedSpringContext() {

    @LocalServerPort
    protected var port: Int = 0

    @Autowired
    protected lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    protected lateinit var toolRegistry: ToolHandlerRegistry

    /** Seeds account preconditions through the domain services - see [AccountFixtures]. */
    @Autowired
    protected lateinit var accountFixtures: AccountFixtures

    @Autowired
    protected lateinit var smsGateway: SmsGateway

    @Autowired
    protected lateinit var mailServer: MailServer

    // The JDK's default request factory can't send PATCH; HttpClient5 (already a test dep) can.
    protected val restTemplate = RestTemplate(HttpComponentsClientHttpRequestFactory()).apply {
        interceptors.add(ContractStatusCheck())
    }

    protected val mapType = object : ParameterizedTypeReference<Map<String, Any?>>() {}

    protected var currentBindingKeyRef: String = ""

    private val scenarioSetups = mutableListOf<() -> Unit>()

    /** Runs [setup] wherever the database is wiped, e.g. to stub a mock the `when` already needs. */
    protected fun beforeScenario(setup: () -> Unit) {
        scenarioSetups += setup
    }

    init {
        // The database is wiped before every `when` (and before a `then` straight under `given`), so
        // the `when` acts once and its `then`s only check (AGENTS.md, Testregeln).
        beforeAny { testCase ->
            if (startsScenario(testCase)) {
                resetDatabase()
                scenarioSetups.forEach { it() }
            }
        }
    }

    private fun startsScenario(testCase: TestCase): Boolean {
        val isWhen = testCase.name.isWhen()
        val underWhen = testCase.parent?.name?.isWhen() == true
        return isWhen || (testCase.type == TestType.Test && !underWhen)
    }

    private fun TestName.isWhen() = prefix?.trim()?.startsWith("When", ignoreCase = true) == true

    /**
     * Wipes every table a suite may touch. Runs before each test; the model-based test also calls
     * it before every generated run.
     */
    protected fun resetDatabase() {
        // Children first (FK order). The personenverzeichnis seed data stays. account's own
        // children cascade; the tools' working data goes with orchestrator.tool_session (ADR-49).
        listOf(
            "auth_sms.enrollment", "auth_password.enrollment", "auth_device.enrollment", "auth_kobil.enrollment",
            // The foreign system's rows: a test must not inherit a device binding from the previous one.
            "kobil.ssms_assertion", "kobil.ssms_user",
            "auth_qr.login_request", "auth_qr.enrollment",
            "orchestrator.tool_session", "orchestrator.auth_journey", "orchestrator.journey_trace",
            "orchestrator.channel_session", "orchestrator.app_token_session", "orchestrator.session_evidence", "account.account",
            // No foreign key - the change log outlives accounts on purpose (ADR-39), so it is wiped by name.
            "account.change_log",
            "orchestrator.device_account_link", "orchestrator.rate_limit", "orchestrator.tool_availability", "orchestrator.dpop_proof_replay",
            "orchestrator.feature_flag"
        ).forEach { jdbcTemplate.update("DELETE FROM $it") }
    }

    /** Moves every proof of [channelSessionId] back by [minutes], as if made that long ago. */
    protected fun ageProofs(channelSessionId: UUID, minutes: Long) {
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
        check(aged != json) { "no proof to age in channel $channelSessionId" }
        jdbcTemplate.update("UPDATE orchestrator.session_evidence SET methods = ? FORMAT JSON WHERE id = ?", aged, evidenceId)
    }

    /**
     * Stubs DpopValidator to return a fake JWK that [jwkThumbprintService] maps to a fresh
     * [currentBindingKeyRef]. Call from a subclass's `beforeEach`. Device-binding tests do not use
     * it: a mocked thumbprint would defeat testing key binding.
     */
    protected fun stubDpopWithFakeJwk() {
        val fakeJwk = mockk<JWK>()
        every { dpopValidator.validate(any(), any(), any()) } returns DpopProof(
            token = "mock-token",
            publicKey = fakeJwk,
            jti = UUID.randomUUID().toString(),
            htm = "POST",
            htu = "http://localhost/mock",
            issuedAt = Instant.now(),
            nonce = null
        )
        currentBindingKeyRef = "binding-" + UUID.randomUUID()
        // Lazy: a test can reassign currentBindingKeyRef mid-run to simulate a mismatched key.
        every { jwkThumbprintService.computeThumbprint(fakeJwk) } answers { currentBindingKeyRef }
    }

    protected fun headers(): HttpHeaders = HttpHeaders().apply {
        set("DPoP", "mock-dpop-token")
        set("Content-Type", "application/json")
    }

    protected fun post(url: String, body: String = "{}"): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url",
            HttpMethod.POST,
            HttpEntity(if (url == "/orchestrator/api/v1/app/channels") withDefaultAvailableTools(body) else body, headers()),
            mapType
        ).let { it.statusCode.is2xxSuccessful shouldBe true; it.body!! }
            .also { response -> checkStartStep(url, body, response) }

    /**
     * A tool activated without input must start on the step its module declares
     * (`ToolModule`'s `startStep`): the journey announces that step before the tool runs. Checked on
     * every activation of every integration test, so no list of tools has to be kept by hand. An
     * activation with input may skip ahead (`approve-qr` with a pairing code), so it is left out.
     */
    private fun checkStartStep(url: String, body: String, response: Map<String, Any?>) {
        val toolId = ACTIVATION.matchEntire(url)?.groupValues?.get(1) ?: return
        if (body.isNotBlank() && body.trim() != "{}") return
        val next = response["next"] as? Map<*, *> ?: return
        if (next["type"] != "tool" || next["toolId"] != toolId || next["toolSessionId"] == null) return
        val declared = toolRegistry.toolOf(ToolId(toolId)).startStep
        withClue("$toolId starts on '${next["step"]}', but its module declares startStep '$declared'") {
            next["step"] shouldBe declared
        }
    }

    /**
     * `availableTools` is required on channel creation (docs/03-tool-architektur.md). Unless a test
     * declares its own set, it gets the full catalog: the neutral default for flows not about
     * availability. [KeycloakChannelIntegrationTest] uses it for its own PATCH bodies.
     */
    protected fun withDefaultAvailableTools(body: String): String {
        if (body.contains("availableTools")) return body
        val allToolIds = toolRegistry.tools().joinToString(",", "[", "]") { "\"${it.toolId}\"" }
        return if (body.isBlank() || body.trim() == "{}") {
            """{"availableTools":$allToolIds}"""
        } else {
            body.trim().removeSuffix("}") + ""","availableTools":$allToolIds}"""
        }
    }

    protected fun patch(url: String, body: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, headers()), mapType
        ).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    protected fun put(url: String, body: String): HttpStatus =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.PUT, HttpEntity(body, if (url.startsWith(ADMIN_API)) adminHeaders() else headers()), Void::class.java
        ).statusCode as HttpStatus

    /** Operator endpoints sit behind the admin login (AdminSecurityConfig) - the demo credentials from application.yml. */
    protected fun adminHeaders(): HttpHeaders = HttpHeaders().apply {
        setBasicAuth("admin", "admin")
        set("Content-Type", "application/json")
    }

    protected fun get(url: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.GET, HttpEntity<Void>(headers()), mapType
        ).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    protected fun delete(url: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.DELETE, HttpEntity<Void>(headers()), mapType
        ).let { it.statusCode shouldBe HttpStatus.OK; it.body!! }

    /** Logout returns 204 No Content (docs/05-api.md), no body to parse. */
    protected fun deleteNoContent(url: String): HttpStatus =
        restTemplate.exchange(
            "http://localhost:$port$url", HttpMethod.DELETE, HttpEntity<Void>(headers()), Void::class.java
        ).statusCode as HttpStatus

    /**
     * `toolSessionId` (docs/05-api.md #2) is stripped here so the many exact-map assertions in
     * the concrete suites stay focused on routing (type/toolId|context/step) without each needing
     * to know the concrete session id; use [nextRaw] where the id itself is under test.
     */
    @Suppress("UNCHECKED_CAST")
    protected fun Map<String, Any?>.next(): Map<String, Any?> = (this["next"] as Map<String, Any?>).minus("toolSessionId")

    @Suppress("UNCHECKED_CAST")
    protected fun Map<String, Any?>.nextRaw(): Map<String, Any?> = this["next"] as Map<String, Any?>

    /** The channel-level block every response carries (docs/05-api.md #2). */
    @Suppress("UNCHECKED_CAST")
    protected fun Map<String, Any?>.channel(): Map<String, Any?> = this["channel"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    protected fun Map<String, Any?>.stepData(): Map<String, Any?> = this["stepData"] as Map<String, Any?>

    /** activeMethods/GET methods entries are {id, method, label} objects - pulls just the method names. */
    @Suppress("UNCHECKED_CAST")
    protected fun List<*>.methodNames(): List<String> = (this as List<Map<String, Any?>>).map { it["method"] as String }

    /**
     * The code the simulated SMS provider or mail server sent while [block] ran - read from their
     * outboxes (sms/mail), never from a log and never from the response (docs/05-api.md).
     */
    protected fun captureMockTan(block: () -> Map<String, Any?>): Pair<String, Map<String, Any?>> {
        val smsBefore = smsGateway.outbox().firstOrNull()?.sequence ?: 0
        val mailBefore = mailServer.outbox().firstOrNull()?.sequence ?: 0
        val response = block()
        val sms = smsGateway.outbox().firstOrNull()?.takeIf { it.sequence > smsBefore }
        val mail = mailServer.outbox().firstOrNull()?.takeIf { it.sequence > mailBefore }
        val tan = sms?.tan ?: mail?.code ?: error("Neither an SMS nor a mail was sent")
        return tan to response
    }

    /**
     * Creates a channel and runs ident-fsc through to Identified with the standard test person;
     * returns the channelSessionId. The address obligation REGISTER raises next stays open. Use
     * [identifyAndConfirmEmail] to get to the point where methods can be enrolled. Keeping this
     * sequence in one place means a change to the registration order touches one helper.
     */
    protected fun identify(
        requiredAcr: String? = null,
        intent: String? = null,
        availableTools: List<String>? = null
    ): String {
        val options = buildList {
            requiredAcr?.let { add(""""requiredAcr":"$it"""") }
            intent?.let { add(""""intent":"$it"""") }
            availableTools?.let { tools -> add(""""availableTools":[${tools.joinToString(",") { "\"$it\"" }}]""") }
        }
        val body = options.takeIf { it.isNotEmpty() }?.joinToString(",", "{", "}")
        val channelSessionId = (
            if (body == null) post("/orchestrator/api/v1/app/channels")
            else post("/orchestrator/api/v1/app/channels", body)
            ).channel()["channelSessionId"] as String
        reIdentifyViaFsc(channelSessionId)
        return channelSessionId
    }

    /**
     * [identify] plus the address confirmation that REGISTER requires before any enrollment
     * (docs/04-orchestrierung.md, "Pflichten sind Zustände").
     */
    protected fun identifyAndConfirmEmail(
        requiredAcr: String? = null,
        intent: String? = null,
        availableTools: List<String>? = null
    ): String {
        val channelSessionId = identify(requiredAcr, intent, availableTools)
        confirmEmailIfRequested(channelSessionId)
        return channelSessionId
    }

    /**
     * Discharges the address step only if the journey is actually asking for it right now - a
     * second run into an account that already confirmed one never gets offered it again.
     */
    protected fun confirmEmailIfRequested(channelSessionId: String) {
        val current = get("/orchestrator/api/v1/channels/$channelSessionId").nextRaw()
        if (current["toolId"] == "confirm-email") confirmEmail(channelSessionId)
    }

    /**
     * Re-identifies via ident-fsc with the standard test person on a channel that offers it (fresh
     * channel, or mid step-up after [triggerEnrollmentStepUp]). Does not assert the preceding
     * `next`, so it is independent of how many identification methods the catalog offers.
     */
    protected fun reIdentifyViaFsc(channelSessionId: String): Map<String, Any?> {
        val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
        return patch(
            "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
            """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
        )
    }

    /**
     * Triggers a step-up by requesting an enrollment while the session evidence is below the
     * required floor. Asserts only the channel state; the offered candidates are a catalog detail.
     */
    protected fun triggerEnrollmentStepUp(channelSessionId: String): Map<String, Any?> {
        val started = post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
        started.channel()["state"] shouldBe "STEP_UP_IN_PROGRESS"
        return started
    }

    /**
     * Runs confirm-email through to Completed and returns the confirmed address. Reuses a running
     * confirm-email tool session, as a real client following `next` would: the enroll-first
     * REGISTER variant opens it as its first step.
     */
    protected fun confirmEmail(channelSessionId: String): String {
        val email = "max.mustermann+${UUID.randomUUID()}@example.com"
        val current = get("/orchestrator/api/v1/channels/$channelSessionId").nextRaw()
        val confirmToolSessionId = (current["toolSessionId"] as? String)?.takeIf { current["toolId"] == "confirm-email" }
            ?: post("/orchestrator/api/v1/channels/$channelSessionId/tools/confirm-email").nextRaw()["toolSessionId"] as String
        val (code, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-email", """{"email":"$email"}""")
        }
        patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-email", """{"code":"$code"}""")
        return email
    }

    /**
     * Activates email as a login method in one shot, since confirm-email already proved control
     * over the address. Separate from [confirmEmail]: confirming is account infrastructure.
     */
    protected fun enrollEmailMethod(channelSessionId: String) {
        post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-email")
    }

    /** Runs enroll-password through to Completed; it discharges the factor-kind obligation after sms. */
    protected fun enrollPassword(channelSessionId: String, password: String = "correct-horse-battery") {
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$toolSessionId/enroll-password", """{"password":"$password"}""")
    }

    /** Runs enroll-sms through to Completed on the given channel. */
    protected fun enrollSms(channelSessionId: String) {
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
        val (tan, _) = captureMockTan {
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
        }
        patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
    }

    /**
     * Seeds an account whose only login method is sms (loa1), bound to this test's device, and
     * returns its id. Seeded via [AccountFixtures]: these tests need the account, not its
     * registration path.
     */
    protected fun registerWithSmsOnly(): AccountId =
        accountFixtures.seedAccount(
            methods = listOf(AccountFixtures.Method.Sms()),
            bindDeviceKeyRef = currentBindingKeyRef
        )

    /** Runs auth-sms through to Completed on the given channel and returns the final response. */
    protected fun authenticateViaSms(channelSessionId: String): Map<String, Any?> {
        val (tan, activation) = captureMockTan {
            post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
        }
        val authToolSessionId = activation.nextRaw()["toolSessionId"] as String
        return patch("/orchestrator/api/v1/tools/$authToolSessionId/auth-sms", """{"tan":"$tan"}""")
    }

    /** Runs auth-password through to Completed on the given channel and returns the final response. */
    protected fun authenticateViaPassword(
        channelSessionId: String,
        password: String = "correct-horse-battery"
    ): Map<String, Any?> {
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password")
            .nextRaw()["toolSessionId"] as String
        return patch("/orchestrator/api/v1/tools/$toolSessionId/auth-password", """{"password":"$password"}""")
    }

    /**
     * Seeds the account [registerAndAuthenticate] would leave behind (sms + password, confirmed
     * address, bound to this device) without opening a channel. Independent of the registration
     * journey's step order.
     */
    protected fun seedRegisteredAccount(): AccountId =
        accountFixtures.seedAccount(
            methods = listOf(AccountFixtures.Method.Sms(), AccountFixtures.Method.Password()),
            bindDeviceKeyRef = currentBindingKeyRef
        )

    /**
     * Seeds a registered account and logs into it on a fresh loa2 channel (sms + password):
     * the "returning user on a known device". Unlike [registerAndAuthenticate] the channel carries
     * no `fsc` identification evidence; tests that need it use the real journey.
     */
    protected fun loginAsSeededAccount(): String {
        seedRegisteredAccount()
        val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
            .channel()["channelSessionId"] as String
        authenticateViaSms(channelSessionId)
        authenticateViaPassword(channelSessionId)
        return channelSessionId
    }

    /**
     * Runs ident-fsc + confirm-email + enroll-sms + enroll-password through to AUTHENTICATED,
     * returns the channelSessionId. The address is confirmed even though sms alone already reaches
     * the default loa1 floor: a confirmed email is a Required Action of REGISTRATION
     * (docs/04-orchestrierung.md #2), not just an ACR-driven candidate.
     */
    protected fun registerAndAuthenticate(): String {
        val channelSessionId = identify()
        // The obligations in their reachable order: the address gates enroll-password, so it
        // comes first. Then a login method, then a second factor kind (the password).
        confirmEmail(channelSessionId)
        enrollSms(channelSessionId)
        enrollPassword(channelSessionId)
        return channelSessionId
    }

    /**
     * Seeds an account with sms + password (plus, optionally, email as a login method) bound to
     * this test's device, and returns its confirmed address. Domain-service seeding rather than a
     * click path - see [AccountFixtures].
     */
    protected fun registerWithEmailAndPassword(
        password: String = "correct-horse-battery",
        /** Also activate email as a login method (a separate act, ADR-17), needed by auth-email*. */
        alsoEnrollEmailMethod: Boolean = false
    ): String {
        accountFixtures.seedAccount(
            methods = buildList {
                add(AccountFixtures.Method.Sms())
                add(AccountFixtures.Method.Password(password))
                if (alsoEnrollEmailMethod) add(AccountFixtures.Method.Email)
            },
            bindDeviceKeyRef = currentBindingKeyRef
        )
        return AccountFixtures.EMAIL
    }
}

/** `POST …/channels/{channelSessionId}/tools/{toolId}`: activating a tool. */
private val ACTIVATION = Regex("/orchestrator/api/v1/channels/[^/]+/tools/([a-z-]+)")
