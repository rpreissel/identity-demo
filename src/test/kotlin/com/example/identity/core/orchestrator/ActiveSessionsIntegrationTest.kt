package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.admin.ActiveSessions
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * The session overview and the demo reset: which channels count as live, the newest ten, the
 * count per channel type, and the two ways in - with admin login and on the public demo path.
 */
class ActiveSessionsIntegrationTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var channelSessionRepository: ChannelSessionRepository

    @Autowired
    private lateinit var activeSessions: ActiveSessions

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    /** A channel created [age] ago; the entity sets its own timestamps, so they are moved back here. */
    private fun channel(
        type: ChannelType,
        state: ChannelState = ChannelState.ANONYMOUS,
        age: Duration = Duration.ZERO,
        expiresIn: Duration = Duration.ofMinutes(30),
        accountId: AccountId? = null,
    ): ChannelSession {
        val created = Instant.now().minus(age)
        return channelSessionRepository.save(
            ChannelSession(type, if (type == ChannelType.APP) "key-${age.seconds}" else null, Instant.now().plus(expiresIn), now = Instant.now()).apply {
                this.state = state
                this.subject = accountId?.let(Subject::Account)
                createdAt = created
                lastAccessedAt = created
            },
        )
    }

    init {
        given("live, logged-out and expired channels of both types") {
            `when`("the overview is read") {
                val accountId = seedRegisteredAccount()
                val app = channel(ChannelType.APP, ChannelState.REGISTERING, accountId = accountId)
                val web = channel(ChannelType.WEB, age = Duration.ofMinutes(1))
                channel(ChannelType.APP, ChannelState.LOGGED_OUT)
                channel(ChannelType.WEB, ChannelState.EXPIRED)
                channel(ChannelType.APP, expiresIn = Duration.ofMinutes(-1))

                val report = activeSessions.report()

                then("only the live ones count, per type and in total") {
                    report.channels.total shouldBe 2
                    report.channels.perType.associate { it.channel to it.count } shouldBe mapOf(ChannelType.APP to 1L, ChannelType.WEB to 1L)
                    report.channels.newest.map { it.channelSessionId } shouldContainExactly listOf(app.channelSessionId, web.channelSessionId)
                }
                then("a channel with an account shows the account and its holder, one without shows neither") {
                    val appView = report.channels.newest.first()
                    appView.accountId shouldBe accountId
                    appView.displayName shouldBe "Max Muster"
                    appView.state shouldBe ChannelState.REGISTERING
                    report.channels.newest.last().displayName.shouldBeNull()
                }
                then("there is no Keycloak part - the test profile runs without Keycloak") {
                    report.keycloak.shouldBeNull()
                }
            }
        }

        given("more than ten live channels") {
            `when`("the overview is read") {
                val channels = (0 until 12).map { channel(ChannelType.APP, age = Duration.ofMinutes(it.toLong())) }

                val report = activeSessions.report()

                then("the list holds the ten newest, newest first, and the count all of them") {
                    report.channels.total shouldBe 12
                    report.channels.newest.map { it.channelSessionId } shouldContainExactly channels.take(10).map { it.channelSessionId }
                }
            }
        }

        given("one live WEB channel") {
            `when`("the operator reads the overview with admin login") {
                channel(ChannelType.WEB)

                val ok = restTemplate.exchange("http://localhost:$port/orchestrator/admin/sessions", HttpMethod.GET, HttpEntity<Void>(adminHeaders()), mapType)

                then("it answers with the count per type") {
                    @Suppress("UNCHECKED_CAST")
                    val perType = ((ok.body!!["channels"] as Map<String, Any?>)["perType"] as List<Map<String, Any?>>).map { it["channel"] to it["count"] }
                    perType shouldContainExactlyInAnyOrder listOf("APP" to 0, "WEB" to 1)
                }
            }
        }

        given("a demo in use: an account with its channel, a channel without account, a running registration, a send limit, a tool lock and the enroll-first order") {
            /** Sets the demo up and returns the channel whose registration is still running. */
            fun demoInUse(): String {
                val accountId = seedRegisteredAccount()
                channel(ChannelType.APP, ChannelState.REGISTERING, accountId = accountId)
                channel(ChannelType.WEB, age = Duration.ofMinutes(1))
                // A fresh App channel on another device starts REGISTER at once, so this one has a running journey.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val registering = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                // A send limit from the run before: the reset must clear it, or starting over is blocked.
                jdbcTemplate.update(
                    "insert into orchestrator.rate_limit (scope, subject, failed_count, updated_at) values ('ACCOUNT', 'x', 5, current_timestamp)"
                )
                put("/orchestrator/admin/tools/auth-sms/availability/APP", """{"enabled":false,"reason":"test"}""") shouldBe HttpStatus.OK
                put("/orchestrator/admin/registration-order", """{"enrollFirst":true}""") shouldBe HttpStatus.OK
                return registering
            }

            `when`("the sessions are read and the demo is reset on the public path, without admin login") {
                val registering = demoInUse()

                val sessions = get("/orchestrator/demo/sessions")
                val reset = restTemplate.exchange("http://localhost:$port/orchestrator/demo/reset", HttpMethod.POST, HttpEntity<Void>(HttpHeaders()), mapType)
                val enrollFirst = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/admin/registration-order", HttpMethod.GET, HttpEntity<Void>(adminHeaders()), mapType
                ).body!!["enrollFirst"]
                val info = restTemplate.getForObject("http://localhost:$port/orchestrator/demo/server-info", Map::class.java)!!

                then("the sessions are shown without login") {
                    @Suppress("UNCHECKED_CAST")
                    (sessions["channels"] as Map<String, Any?>)["total"] shouldBe 3
                }
                then("the reset reports what it removed, and creates nothing again") {
                    reset.statusCode shouldBe HttpStatus.OK
                    // A test person registers like anyone else afterwards.
                    reset.body!!.keys shouldBe setOf("deletedAccounts", "endedSessions")
                    reset.body!!["deletedAccounts"] shouldBe 1
                    // The one ended with its account, and the two without one by the reset itself.
                    reset.body!!["endedSessions"] shouldBe 3
                }
                then("the accounts, the send limits and the live sessions are gone") {
                    jdbcTemplate.queryForObject("select count(*) from account.account", Int::class.java) shouldBe 0
                    // The admin read after the reset books its own login attempt (AdminLoginRateLimitFilter).
                    jdbcTemplate.queryForObject("select count(*) from orchestrator.rate_limit where scope <> 'ADMIN'", Int::class.java) shouldBe 0
                    activeSessions.report().channels.total shouldBe 0
                }
                then("the running registration is cancelled and its session ended") {
                    channelSessionRepository.findById(UUID.fromString(registering)).get().state shouldBe ChannelState.LOGGED_OUT
                }
                then("every setting is back to its preset") {
                    enrollFirst shouldBe false
                    @Suppress("UNCHECKED_CAST")
                    val locks = (info["disabledTools"] as List<Map<String, Any?>>).map { it["toolId"] to it["channel"] }
                    // The ad-hoc lock is gone, the preset ones (demo.tool-defaults) are back.
                    locks shouldNotContain ("auth-sms" to "APP")
                    locks shouldContain ("auth-email" to "APP")
                    locks shouldContain ("auth-device" to "WEB")
                }
            }

            `when`("the operator resets the demo with admin login") {
                demoInUse()

                val reset = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/admin/demo-reset", HttpMethod.POST, HttpEntity<Void>(adminHeaders()), mapType
                )

                then("it is the same reset") {
                    reset.statusCode shouldBe HttpStatus.OK
                    reset.body!!["deletedAccounts"] shouldBe 1
                    reset.body!!["endedSessions"] shouldBe 3
                }
            }
        }
    }
}
