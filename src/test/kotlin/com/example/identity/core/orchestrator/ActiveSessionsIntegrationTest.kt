package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.admin.ActiveSessions
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.Duration
import java.time.Instant

/**
 * The session overview and the demo reset: which channels count as live, the newest ten, the
 * count per channel type, and the two ways in - with admin login and on the public demo path.
 */
class ActiveSessionsIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @Autowired
    private lateinit var channelSessionRepository: ChannelSessionRepository

    @Autowired
    private lateinit var activeSessions: ActiveSessions

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    /** A channel created [age] ago; the entity sets its own timestamps, so they are moved back here. */
    private fun channel(
        type: ChannelType,
        state: ChannelState = ChannelState.ANONYMOUS,
        age: Duration = Duration.ZERO,
        expiresIn: Duration = Duration.ofMinutes(30),
        accountId: Long? = null,
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
            then("only the live ones count, per type and in total") {
                val accountId = seedRegisteredAccount()
                val app = channel(ChannelType.APP, ChannelState.REGISTERING, accountId = accountId)
                val web = channel(ChannelType.WEB, age = Duration.ofMinutes(1))
                channel(ChannelType.APP, ChannelState.LOGGED_OUT)
                channel(ChannelType.WEB, ChannelState.EXPIRED)
                channel(ChannelType.APP, expiresIn = Duration.ofMinutes(-1))

                val report = activeSessions.report()

                report.channels.total shouldBe 2
                report.channels.perType.associate { it.channel to it.count } shouldBe mapOf(ChannelType.APP to 1L, ChannelType.WEB to 1L)
                report.channels.newest.map { it.channelSessionId } shouldContainExactly listOf(app.channelSessionId, web.channelSessionId)
                val appView = report.channels.newest.first()
                appView.accountId shouldBe accountId
                appView.displayName.shouldNotBeNull()
                appView.state shouldBe ChannelState.REGISTERING
                report.channels.newest.last().displayName.shouldBeNull()
                // The test profile runs without Keycloak.
                report.keycloak.shouldBeNull()
            }
        }

        given("more than ten live channels") {
            then("the list holds the ten newest, newest first, and the count all of them") {
                val channels = (0 until 12).map { channel(ChannelType.APP, age = Duration.ofMinutes(it.toLong())) }

                val report = activeSessions.report()

                report.channels.total shouldBe 12
                report.channels.newest.map { it.channelSessionId } shouldContainExactly channels.take(10).map { it.channelSessionId }
            }
        }

        given("the public demo endpoints") {
            then("they show the sessions and reset the demo without admin login") {
                val accountId = seedRegisteredAccount()
                channel(ChannelType.APP, ChannelState.REGISTERING, accountId = accountId)
                channel(ChannelType.WEB, age = Duration.ofMinutes(1))

                // A send limit from the run before: the reset must clear it, or starting over is blocked.
                jdbcTemplate.update(
                    "insert into orchestrator.rate_limit (scope, subject, failed_count, updated_at) values ('ACCOUNT', 'x', 5, current_timestamp)"
                )
                val sessions = get("/orchestrator/demo/sessions")
                @Suppress("UNCHECKED_CAST")
                (sessions["channels"] as Map<String, Any?>)["total"] shouldBe 2

                val reset = restTemplate.exchange("http://localhost:$port/orchestrator/demo/reset", HttpMethod.POST, HttpEntity<Void>(HttpHeaders()), mapType)
                reset.statusCode shouldBe HttpStatus.OK
                reset.body!!["deletedAccounts"] shouldBe 1
                // Both: the one ended with its account and the one without an account.
                reset.body!!["endedSessions"] shouldBe 2
                jdbcTemplate.queryForObject("select count(*) from orchestrator.rate_limit", Int::class.java) shouldBe 0
                jdbcTemplate.queryForObject("select count(*) from account.account", Int::class.java) shouldBe 0
                // The account's channel ends with the account, the one without an account by the reset itself.
                activeSessions.report().channels.total shouldBe 0
            }
        }

        given("a session in the middle of a registration") {
            then("the reset cancels its journey and ends it") {
                // A fresh App channel starts REGISTER at once, so this one has a running journey.
                val registering = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

                val reset = restTemplate.exchange("http://localhost:$port/orchestrator/demo/reset", HttpMethod.POST, HttpEntity<Void>(HttpHeaders()), mapType)
                reset.statusCode shouldBe HttpStatus.OK
                reset.body!!["endedSessions"] shouldBe 1
                channelSessionRepository.findById(java.util.UUID.fromString(registering)).get().state shouldBe ChannelState.LOGGED_OUT
            }
        }

        given("the admin endpoint") {
            then("answers with admin login, and 401 without") {
                channel(ChannelType.WEB)
                val ok = restTemplate.exchange("http://localhost:$port/orchestrator/admin/sessions", HttpMethod.GET, HttpEntity<Void>(adminHeaders()), mapType)
                @Suppress("UNCHECKED_CAST")
                val perType = ((ok.body!!["channels"] as Map<String, Any?>)["perType"] as List<Map<String, Any?>>).map { it["channel"] to it["count"] }
                perType shouldContainExactlyInAnyOrder listOf("APP" to 0, "WEB" to 1)

                assertThrows<HttpClientErrorException> {
                    restTemplate.exchange("http://localhost:$port/orchestrator/admin/sessions", HttpMethod.GET, HttpEntity<Void>(HttpHeaders()), mapType)
                }.statusCode shouldBe HttpStatus.UNAUTHORIZED
            }
        }
    }
}
