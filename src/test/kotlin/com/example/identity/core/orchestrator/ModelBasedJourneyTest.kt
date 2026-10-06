package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.channel.KeycloakChannelService
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import io.kotest.matchers.collections.shouldBeEmpty
import com.example.identity.core.orchestrator.session.AppTokenSessionRepository
import com.example.identity.core.orchestrator.session.AppTokenVault
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.web.client.HttpStatusCodeException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.random.Random

/**
 * Model-based test (docs/invarianten.md): random [Step] sequences against the real HTTP API, with
 * the invariants checked in the database after every step. A failure is shrunk to the shortest
 * sequence and reported with its seed. 4xx answers are expected; a 5xx is a violation.
 */
class ModelBasedJourneyTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var keycloakChannelService: KeycloakChannelService

    @Autowired
    private lateinit var appTokenSessionRepository: AppTokenSessionRepository

    @Autowired
    private lateinit var appTokenVault: AppTokenVault

    private enum class Step {
        OPEN_CHANNEL, SIGN_IN_SMS, WRONG_TAN, SIGN_IN_PASSWORD, REPLAY_LAST_PATCH,
        LOG_OUT, CANCEL_JOURNEY, START_MANAGE, START_PEER_LOGIN, START_STEP_UP, READ_CHANNEL, SWITCH_DEVICE,
        // The session behind the channel (ADR-43): time passes, the clock runs out, Keycloak signs out.
        FETCH_TOKEN, SESSION_AGES, SESSION_LAPSES, KEYCLOAK_SIGN_OUT
    }

    /** What one run remembers between steps - the "model" the random steps act on. */
    private inner class Run(private val deviceA: String) {
        private val deviceB = "binding-" + UUID.randomUUID()
        private var channel: String? = null
        private var lastPatch: Pair<String, String>? = null
        private val endedChannels = mutableMapOf<String, String>()
        private val finishedJourneys = mutableMapOf<String, String>()
        private val sessionOfLogin = mutableMapOf<String, String>()
        private val sessionGone = mutableSetOf<String>()
        private val everSetUp = mutableSetOf<Long>()

        fun perform(step: Step): String? = when (step) {
            Step.OPEN_CHANNEL -> {
                val created = call(HttpMethod.POST, "/orchestrator/api/v1/app/channels", withDefaultAvailableTools("{}")).second
                created?.let(::channelIdOf)?.let { channel = it }
                null
            }
            Step.SIGN_IN_SMS -> signInSms(correct = true)
            Step.WRONG_TAN -> signInSms(correct = false)
            Step.SIGN_IN_PASSWORD -> channel?.let { ch ->
                toolSessionOf(call(HttpMethod.POST, "/tools/api/auth-password/v1?channel=$ch").second)?.let { ts ->
                    patchTool("/tools/api/auth-password/v1/$ts", """{"password":"correct-horse-battery"}""")
                }
            }
            Step.REPLAY_LAST_PATCH -> lastPatch?.let { (url, body) -> serverError(call(HttpMethod.PATCH, url, body)) }
            Step.LOG_OUT -> channel?.let { serverError(call(HttpMethod.DELETE, "/orchestrator/api/v1/channels/$it")) }
            Step.CANCEL_JOURNEY -> channel?.let { serverError(call(HttpMethod.DELETE, "/orchestrator/api/v1/channels/$it/journey")) }
            Step.START_MANAGE -> channel?.let { serverError(call(HttpMethod.POST, "/orchestrator/api/v1/channels/$it/enrollments")) }
            Step.START_PEER_LOGIN -> channel?.let { serverError(call(HttpMethod.POST, "/orchestrator/api/v1/channels/$it/peer-logins")) }
            Step.START_STEP_UP -> channel?.let { serverError(call(HttpMethod.POST, "/orchestrator/api/v1/channels/$it/step-ups", """{"requiredAcr":"loa3"}""")) }
            Step.READ_CHANNEL -> channel?.let { serverError(call(HttpMethod.GET, "/orchestrator/api/v1/channels/$it")) }
            Step.SWITCH_DEVICE -> {
                currentBindingKeyRef = if (currentBindingKeyRef == deviceA) deviceB else deviceA
                channel = null
                null
            }
            Step.FETCH_TOKEN -> channel?.let { serverError(call(HttpMethod.GET, "/orchestrator/api/v1/channels/$it/token")) }
            // A quarter of the window is used, so the next interaction renews the session.
            Step.SESSION_AGES -> {
                channel?.let { ageSession(it, issuedAgo = Duration.ofMinutes(10), windowLeft = Duration.ofMinutes(20)) }
                null
            }
            Step.SESSION_LAPSES -> {
                val ch = channel
                if (ch != null && ageSession(ch, issuedAgo = Duration.ofMinutes(40), windowLeft = Duration.ofSeconds(-1))) sessionGone += ch
                null
            }
            Step.KEYCLOAK_SIGN_OUT -> channel?.let { signOutAtKeycloak(it) }
        }

        /**
         * Moves the clock of [channel]'s open session: the cached token as if issued [issuedAgo], the
         * window and the channel's expiry ending [windowLeft] from now. `false` without an open session;
         * an expired channel is left alone, since time does not run backwards.
         */
        private fun ageSession(channel: String, issuedAgo: Duration, windowLeft: Duration): Boolean {
            val context = jdbcTemplate.queryForList(
                "SELECT a.id FROM orchestrator.channel_session c JOIN orchestrator.app_token_session a ON a.id = c.app_token_session_id " +
                    "WHERE c.id = ? AND c.state IN ('AUTHENTICATED', 'STEP_UP_IN_PROGRESS') AND a.keycloak_session_id IS NOT NULL " +
                    "AND c.expires_at > CURRENT_TIMESTAMP",
                UUID.fromString(channel)
            ).firstOrNull()?.get("ID") ?: return false
            val token = PlainJWT(JWTClaimsSet.Builder().issueTime(Date.from(Instant.now().minus(issuedAgo))).build()).serialize()
            val windowEnd = Timestamp.from(Instant.now().plus(windowLeft))
            // The cached token is sealed under the account's key (ADR-53), so it goes through the vault.
            val tokenSession = appTokenSessionRepository.findById(context as UUID).get()
            appTokenVault.forSession(tokenSession).accessToken = token
            tokenSession.refreshExpiresAt = windowEnd.toInstant()
            appTokenSessionRepository.save(tokenSession)
            jdbcTemplate.update("UPDATE orchestrator.channel_session SET expires_at = ? WHERE id = ?", windowEnd, UUID.fromString(channel))
            return true
        }

        private fun signOutAtKeycloak(channel: String): String? {
            val login = jdbcTemplate.queryForList(
                "SELECT a.account_id, a.keycloak_session_id FROM orchestrator.channel_session c " +
                    "JOIN orchestrator.app_token_session a ON a.id = c.app_token_session_id WHERE c.id = ? AND a.keycloak_session_id IS NOT NULL",
                UUID.fromString(channel)
            ).firstOrNull() ?: return null
            return runCatching {
                keycloakChannelService.signedOutAtKeycloak(Subject.Account(AccountId((login["ACCOUNT_ID"] as Number).toLong())), login["KEYCLOAK_SESSION_ID"] as String)
            }.fold({ sessionGone += channel; null }, { "sign-out failed: $it" })
        }

        private fun signInSms(correct: Boolean): String? {
            val ch = channel ?: return null
            val before = smsGateway.outbox().firstOrNull()?.sequence ?: 0
            val activated = call(HttpMethod.POST, "/tools/api/auth-sms/v1?channel=$ch")
            serverError(activated)?.let { return it }
            val ts = toolSessionOf(activated.second) ?: return null
            val tan = smsGateway.outbox().firstOrNull()?.takeIf { it.sequence > before }?.tan ?: return null
            return patchTool("/tools/api/auth-sms/v1/$ts", """{"tan":"${if (correct) tan else wrongTan(tan)}"}""")
        }

        private fun patchTool(url: String, body: String): String? {
            val result = call(HttpMethod.PATCH, url, body)
            if (result.first in 200..299) lastPatch = url to body
            return serverError(result)
        }

        /** Every invariant of docs/invarianten.md this model can observe - checked after each step. */
        fun violations(): List<String> = buildList {
            // I-1: an ended channel stays ended - in the very state it ended in.
            jdbcTemplate.queryForList("SELECT id, state FROM orchestrator.channel_session").forEach { row ->
                val id = row["ID"].toString()
                val state = row["STATE"].toString()
                endedChannels[id]?.let { ended -> if (state != ended) add("I-1: channel $id went from $ended to $state after it ended") }
                if (state == "LOGGED_OUT" || state == "EXPIRED") endedChannels.putIfAbsent(id, state)
            }
            // I-2: a finished journey never changes again.
            jdbcTemplate.queryForList("SELECT id, lifecycle FROM orchestrator.auth_journey").forEach { row ->
                val id = row["ID"].toString()
                val lifecycle = row["LIFECYCLE"].toString()
                finishedJourneys[id]?.let { before -> if (before != lifecycle) add("I-2: journey $id went from $before to $lifecycle") }
                if (lifecycle in setOf("CONSUMED", "CANCELLED", "FAILED", "EXPIRED")) finishedJourneys[id] = lifecycle
            }
            // I-3: at most one running journey per channel.
            jdbcTemplate.queryForList(
                "SELECT channel_session_id, COUNT(*) AS n FROM orchestrator.auth_journey WHERE lifecycle = 'STARTED' GROUP BY channel_session_id HAVING COUNT(*) > 1"
            ).forEach { add("I-3: channel ${it["CHANNEL_SESSION_ID"]} has ${it["N"]} running journeys") }
            // I-4: an authenticated channel carries evidence.
            jdbcTemplate.queryForList(
                "SELECT c.id FROM orchestrator.channel_session c LEFT JOIN orchestrator.session_evidence e ON e.id = c.session_evidence_id " +
                    "WHERE c.state = 'AUTHENTICATED' AND (e.id IS NULL OR LENGTH(CAST(e.methods AS VARCHAR)) <= 2)"
            ).forEach { add("I-4: channel ${it["ID"]} authenticated without evidence") }
            // I-13: at most one active password per account.
            jdbcTemplate.queryForList(
                "SELECT account_id, COUNT(*) AS n FROM account.auth_method WHERE method = 'password' AND active GROUP BY account_id HAVING COUNT(*) > 1"
            ).forEach { add("I-13: account ${it["ACCOUNT_ID"]} has ${it["N"]} active passwords") }
            // I-14: no device link to a deleted account.
            jdbcTemplate.queryForList(
                "SELECT l.binding_key_ref FROM orchestrator.device_account_link l LEFT JOIN account.account a ON a.id = l.account_id WHERE a.id IS NULL"
            ).forEach { add("I-14: device link ${it["BINDING_KEY_REF"]} points to a deleted account") }
            // I-26: a logged-in channel never works with an account still being set up; REGISTERING is never stored.
            jdbcTemplate.queryForList(
                "SELECT c.id FROM orchestrator.channel_session c WHERE c.state IN ('AUTHENTICATED', 'STEP_UP_IN_PROGRESS') " +
                    "AND c.account_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM account.auth_method m WHERE m.account_id = c.account_id)"
            ).forEach { add("I-26: channel ${it["ID"]} is logged in with an account still being set up") }
            jdbcTemplate.queryForList("SELECT id FROM orchestrator.channel_session WHERE state = 'REGISTERING'")
                .forEach { add("I-26: channel ${it["ID"]} stores REGISTERING, which is only ever derived") }
            // I-23: a logged-in App channel has its session and never outlives the session's window.
            jdbcTemplate.queryForList(
                "SELECT c.id, a.keycloak_session_id, CASE WHEN a.refresh_expires_at IS NULL OR c.expires_at > a.refresh_expires_at " +
                    "THEN 1 ELSE 0 END AS outlives FROM orchestrator.channel_session c " +
                    "LEFT JOIN orchestrator.app_token_session a ON a.id = c.app_token_session_id " +
                    "WHERE c.channel = 'APP' AND c.state IN ('AUTHENTICATED', 'STEP_UP_IN_PROGRESS')"
            ).forEach { row ->
                if (row["KEYCLOAK_SESSION_ID"] == null) add("I-23: channel ${row["ID"]} is logged in without a session")
                else if ((row["OUTLIVES"] as Number).toInt() == 1) add("I-23: channel ${row["ID"]} outlives its session window")
            }
            // I-23: once its session lapsed or was signed out, a channel is no longer usable.
            sessionGone.forEach { id ->
                jdbcTemplate.queryForList(
                    "SELECT state FROM orchestrator.channel_session WHERE id = ? AND state NOT IN ('LOGGED_OUT', 'EXPIRED') AND expires_at > CURRENT_TIMESTAMP",
                    UUID.fromString(id)
                ).forEach { add("I-23: channel $id is still usable (${it["STATE"]}) after its session ended") }
            }
            // I-28: a set-up account never falls back into setup while it exists.
            val setUpNow = jdbcTemplate.queryForList(
                "SELECT a.id FROM account.account a WHERE EXISTS (SELECT 1 FROM account.auth_method m WHERE m.account_id = a.id)", Long::class.java
            ).filterNotNull().toSet()
            val existing = jdbcTemplate.queryForList("SELECT id FROM account.account", Long::class.java).filterNotNull().toSet()
            everSetUp.filter { it in existing && it !in setUpNow }.forEach { add("I-28: account $it fell back into setup") }
            everSetUp.addAll(setUpNow)
            // I-24: a login's session is opened once and never replaced.
            jdbcTemplate.queryForList("SELECT id, keycloak_session_id FROM orchestrator.app_token_session WHERE keycloak_session_id IS NOT NULL").forEach { row ->
                val id = row["ID"].toString()
                val session = row["KEYCLOAK_SESSION_ID"].toString()
                sessionOfLogin[id]?.let { before -> if (before != session) add("I-24: login $id switched from session $before to $session") }
                sessionOfLogin.putIfAbsent(id, session)
            }
        }
    }

    /** (status, body or null). Never throws for an HTTP status - 4xx is expected, 5xx is judged by the caller. */
    private fun call(method: HttpMethod, url: String, body: String? = null): Pair<Int, Map<String, Any?>?> =
        try {
            val response = restTemplate.exchange("http://localhost:$port$url", method, HttpEntity(body, headers()), mapType)
            response.statusCode.value() to response.body
        } catch (e: HttpStatusCodeException) {
            e.statusCode.value() to null
        }

    private fun serverError(result: Pair<Int, Map<String, Any?>?>): String? =
        if (result.first >= 500) "HTTP ${result.first}" else null

    @Suppress("UNCHECKED_CAST")
    private fun channelIdOf(body: Map<String, Any?>): String? = (body["channel"] as? Map<String, Any?>)?.get("channelSessionId") as? String

    @Suppress("UNCHECKED_CAST")
    private fun toolSessionOf(body: Map<String, Any?>?): String? = (body?.get("next") as? Map<String, Any?>)?.get("toolSessionId") as? String

    private fun wrongTan(tan: String) = if (tan == "000000") "000001" else "000000"

    /**
     * Runs [steps] from a clean database, with a fresh device key and the registered account; the
     * first violation with the step index, or null. One `when` runs many of these.
     */
    private fun execute(steps: List<Step>): String? {
        resetDatabase()
        stubDpopWithFakeJwk()
        seedRegisteredAccount()
        val run = Run(deviceA = currentBindingKeyRef)
        steps.forEachIndexed { index, step ->
            run.perform(step)?.let { return "step $index ($step): $it" }
            run.violations().firstOrNull()?.let { return "step $index ($step): $it" }
        }
        return null
    }

    /** Drops steps one at a time as long as the run still fails - the shortest failing sequence. */
    private fun shrink(steps: List<Step>): Pair<List<Step>, String> {
        var current = steps
        var failure = checkNotNull(execute(current))
        var progress = true
        while (progress) {
            progress = false
            for (i in current.indices) {
                val candidate = current.filterIndexed { index, _ -> index != i }
                val result = execute(candidate) ?: continue
                current = candidate
                failure = result
                progress = true
                break
            }
        }
        return current to failure
    }

    init {
        given("random sequences of channel and journey actions") {
            `when`("$SEEDS seeded runs of $STEPS_PER_RUN steps each are executed, failures shrunk") {
                val failures = (1..SEEDS).mapNotNull { seed ->
                    val random = Random(seed)
                    val steps = listOf(Step.OPEN_CHANNEL) + List(STEPS_PER_RUN) { Step.entries[random.nextInt(Step.entries.size)] }
                    if (execute(steps) == null) return@mapNotNull null
                    val (minimal, failure) = shrink(steps)
                    "seed $seed: $failure - shortest sequence: $minimal"
                }

                then("no step ever breaks an invariant of docs/invarianten.md") {
                    failures.shouldBeEmpty()
                }
            }

            `when`("the sequences it once found are executed again") {
                val failures = listOf(
                    // A tool PATCH replayed after logout must not re-authenticate the channel.
                    listOf(Step.OPEN_CHANNEL, Step.SIGN_IN_SMS, Step.LOG_OUT, Step.REPLAY_LAST_PATCH),
                    // Method management, then a peer login, must not leave two running journeys.
                    listOf(
                        Step.OPEN_CHANNEL, Step.SIGN_IN_PASSWORD, Step.START_MANAGE, Step.SIGN_IN_SMS,
                        Step.CANCEL_JOURNEY, Step.START_MANAGE, Step.START_PEER_LOGIN
                    ),
                    // A step-up must not revive a logged-out channel.
                    listOf(Step.OPEN_CHANNEL, Step.SIGN_IN_SMS, Step.LOG_OUT, Step.START_STEP_UP),
                    // A step-up on a channel without an account must not crash.
                    listOf(Step.SWITCH_DEVICE, Step.OPEN_CHANNEL, Step.START_STEP_UP),
                    // A cancelled step-up before the login must not fall back to AUTHENTICATED (I-4).
                    listOf(Step.OPEN_CHANNEL, Step.START_STEP_UP, Step.CANCEL_JOURNEY),
                    // An interaction after a quarter of the window renews the same session (I-23, I-24).
                    listOf(Step.OPEN_CHANNEL, Step.SIGN_IN_SMS, Step.SESSION_AGES, Step.START_MANAGE, Step.FETCH_TOKEN),
                    // A lapsed session is not revived by a step-up, a token request or a sign-in (I-23, I-24).
                    listOf(Step.OPEN_CHANNEL, Step.SIGN_IN_SMS, Step.START_STEP_UP, Step.SESSION_LAPSES, Step.SIGN_IN_PASSWORD, Step.FETCH_TOKEN),
                    // A sign-out at Keycloak ends the App channel mid step-up (I-23).
                    listOf(Step.OPEN_CHANNEL, Step.SIGN_IN_SMS, Step.START_STEP_UP, Step.KEYCLOAK_SIGN_OUT, Step.SIGN_IN_PASSWORD),
                ).mapNotNull { steps -> execute(steps)?.let { "$steps: $it" } }

                then("they stay fixed - independent of which seeds happen to reach them") {
                    failures.shouldBeEmpty()
                }
            }
        }
    }

    private companion object {
        /** 50 seeds keep the regular run short; `-PmodelSeeds=1000` searches deeper (docs/13-ausfuehren.md). */
        val SEEDS = System.getProperty("model.seeds")?.toInt() ?: 50
        const val STEPS_PER_RUN = 14
    }
}
