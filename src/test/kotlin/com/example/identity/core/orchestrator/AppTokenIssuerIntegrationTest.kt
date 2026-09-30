package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.channel.KcChannelService
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * ADR-43 in the default profile, where the mock token service plays Keycloak's role: reaching
 * AUTHENTICATED opens the session, the channel lives exactly as long as the session window, and a
 * sign-out Keycloak reports ends the App channel whose login held that session.
 */
class AppTokenIssuerIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @Autowired
    private lateinit var kcChannelService: KcChannelService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun channelExpiresAt(channel: String): Instant = jdbcTemplate.queryForObject(
        "SELECT expires_at FROM orchestrator.channel_session WHERE id = ?", Timestamp::class.java, UUID.fromString(channel)
    )!!.toInstant()

    private fun appTokenSessionId(channel: String): UUID = jdbcTemplate.queryForObject(
        "SELECT app_token_session_id FROM orchestrator.channel_session WHERE id = ?", UUID::class.java, UUID.fromString(channel)
    )!!

    private fun refreshExpiresAt(appTokenSessionId: UUID): Instant? = jdbcTemplate.queryForObject(
        "SELECT refresh_expires_at FROM orchestrator.app_token_session WHERE id = ?", Timestamp::class.java, appTokenSessionId
    )?.toInstant()

    private fun stateOf(channel: String): String = jdbcTemplate.queryForObject(
        "SELECT state FROM orchestrator.channel_session WHERE id = ?", String::class.java, UUID.fromString(channel)
    )!!

    init {
        given("an App channel before authentication") {
            then("the fixed channel lifetime applies") {
                seedRegisteredAccount()
                val channel = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String

                Duration.between(Instant.now(), channelExpiresAt(channel)).toHours() shouldBe 23L
            }
        }

        given("an App channel that reaches AUTHENTICATED") {
            then("the session is opened in the same transition, before any token request, and the channel lives as long as its window") {
                val channel = loginAsSeededAccount()

                val context = appTokenSessionId(channel)
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orchestrator.app_token_session WHERE id = ? AND refresh_token IS NOT NULL AND access_token IS NOT NULL",
                    Int::class.java, context
                ) shouldBe 1
                val window = refreshExpiresAt(context).shouldNotBeNull()
                channelExpiresAt(channel) shouldBe window
                Duration.between(Instant.now(), window).toMinutes() shouldBeLessThan 31L
            }
        }

        given("an authenticated App channel whose token is refreshed") {
            then("every refresh moves the channel's expiry to the new session window") {
                val channel = loginAsSeededAccount()
                val context = appTokenSessionId(channel)
                jdbcTemplate.update(
                    "UPDATE orchestrator.app_token_session SET access_expires_at = DATEADD('SECOND', -10, CURRENT_TIMESTAMP), " +
                        "refresh_expires_at = DATEADD('SECOND', 60, CURRENT_TIMESTAMP) WHERE id = ?",
                    context
                )
                jdbcTemplate.update(
                    "UPDATE orchestrator.channel_session SET expires_at = DATEADD('SECOND', 60, CURRENT_TIMESTAMP) WHERE id = ?",
                    UUID.fromString(channel)
                )

                get("/orchestrator/api/v1/channels/$channel/token")

                val window = refreshExpiresAt(context).shouldNotBeNull()
                Duration.between(Instant.now(), window).toMinutes() shouldBe 29L
                channelExpiresAt(channel) shouldBe window
            }
        }

        given("a journey interaction on an authenticated App channel") {
            /** The cached token as if minted [minutesAgo], with the window ending [windowLeft] from now. */
            fun ageToken(channel: String, minutesAgo: Long, windowLeft: Duration) {
                val issued = Instant.now().minus(Duration.ofMinutes(minutesAgo))
                val token = PlainJWT(JWTClaimsSet.Builder().issueTime(Date.from(issued)).build()).serialize()
                val windowEnd = Timestamp.from(Instant.now().plus(windowLeft))
                jdbcTemplate.update(
                    "UPDATE orchestrator.app_token_session SET access_token = ?, refresh_expires_at = ? WHERE id = ?",
                    token, windowEnd, appTokenSessionId(channel)
                )
                jdbcTemplate.update("UPDATE orchestrator.channel_session SET expires_at = ? WHERE id = ?", windowEnd, UUID.fromString(channel))
            }
            fun cachedToken(channel: String): String = jdbcTemplate.queryForObject(
                "SELECT access_token FROM orchestrator.app_token_session WHERE id = ?", String::class.java, appTokenSessionId(channel)
            )!!

            then("once a quarter of the window is used, it renews the session and moves the channel's expiry") {
                val channel = loginAsSeededAccount()
                ageToken(channel, minutesAgo = 10, windowLeft = Duration.ofMinutes(20))
                val aged = cachedToken(channel)

                post("/orchestrator/api/v1/channels/$channel/logouts")

                cachedToken(channel) shouldNotBe aged
                val window = refreshExpiresAt(appTokenSessionId(channel)).shouldNotBeNull()
                Duration.between(Instant.now(), window).toMinutes() shouldBe 29L
                channelExpiresAt(channel) shouldBe window
            }

            then("shortly after the last token, the current window stands") {
                val channel = loginAsSeededAccount()
                val token = cachedToken(channel)
                val expiresAt = channelExpiresAt(channel)

                post("/orchestrator/api/v1/channels/$channel/logouts")

                cachedToken(channel) shouldBe token
                channelExpiresAt(channel) shouldBe expiresAt
            }

            then("a session that can no longer be renewed ends the channel for good: 410 and EXPIRED") {
                val channel = loginAsSeededAccount()
                // The session's window has lapsed, the channel's own row not yet: Keycloak ended it early.
                ageToken(channel, minutesAgo = 10, windowLeft = Duration.ofMinutes(-1))
                jdbcTemplate.update(
                    "UPDATE orchestrator.channel_session SET expires_at = DATEADD('MINUTE', 5, CURRENT_TIMESTAMP) WHERE id = ?",
                    UUID.fromString(channel)
                )

                assertThrows<HttpClientErrorException> { post("/orchestrator/api/v1/channels/$channel/logouts") }
                    .statusCode shouldBe HttpStatus.GONE

                stateOf(channel) shouldBe "EXPIRED"
            }
        }

        given("an authenticated App channel whose session window has passed") {
            then("the channel is refused like any expired one") {
                val channel = loginAsSeededAccount()
                jdbcTemplate.update(
                    "UPDATE orchestrator.channel_session SET expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?",
                    UUID.fromString(channel)
                )

                assertThrows<HttpClientErrorException> { get("/orchestrator/api/v1/channels/$channel") }
                    .statusCode shouldBe HttpStatus.NOT_FOUND
                assertThrows<HttpClientErrorException> { get("/orchestrator/api/v1/channels/$channel/token") }
                    .statusCode shouldBe HttpStatus.NOT_FOUND
            }
        }

        given("Keycloak reports the sign-out of the session an App login holds") {
            then("that App channel ends, another login of the same account does not") {
                val accountId = seedRegisteredAccount()
                fun login(): String {
                    val channel = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
                    authenticateViaSms(channel)
                    authenticateViaPassword(channel)
                    return channel
                }
                val signedOut = login()
                val other = login()
                // The mock provider has no `sid`; under `keycloak` KcTokenProvider records it from the token.
                jdbcTemplate.update("UPDATE orchestrator.app_token_session SET keycloak_session_id = 'kc-session-1' WHERE id = ?", appTokenSessionId(signedOut))
                jdbcTemplate.update("UPDATE orchestrator.app_token_session SET keycloak_session_id = 'kc-session-2' WHERE id = ?", appTokenSessionId(other))

                kcChannelService.signedOutAtKeycloak(Subject.Account(accountId), "kc-session-1")

                stateOf(signedOut) shouldBe "LOGGED_OUT"
                stateOf(other) shouldBe "AUTHENTICATED"
                assertThrows<HttpClientErrorException> { get("/orchestrator/api/v1/channels/$signedOut/token") }
                    .statusCode shouldBe HttpStatus.CONFLICT
            }
        }

        given("a step-up on an authenticated App channel") {
            fun sessionOf(channel: String): String = jdbcTemplate.queryForObject(
                "SELECT keycloak_session_id FROM orchestrator.app_token_session WHERE id = ?", String::class.java, appTokenSessionId(channel)
            )!!

            then("the transition back to AUTHENTICATED re-mints the token with the raised acr, in the same session") {
                seedRegisteredAccount()
                val channel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                authenticateViaSms(channel)
                fun tokenAcr() = PlainJWT.parse(get("/orchestrator/api/v1/channels/$channel/token")["accessToken"] as String)
                    .jwtClaimsSet.getStringClaim("acr")
                tokenAcr() shouldBe "loa1"
                val session = sessionOf(channel)

                post("/orchestrator/api/v1/channels/$channel/step-ups", """{"requiredAcr":"loa2"}""")
                authenticateViaPassword(channel)["next"].shouldNotBeNull()

                val cached = jdbcTemplate.queryForObject(
                    "SELECT access_token FROM orchestrator.app_token_session WHERE id = ?", String::class.java, appTokenSessionId(channel)
                )
                PlainJWT.parse(cached).jwtClaimsSet.getStringClaim("acr") shouldBe "loa2"
                tokenAcr() shouldBe "loa2"
                stateOf(channel) shouldBe "AUTHENTICATED"
                sessionOf(channel) shouldBe session
            }

            then("if the session window lapsed meanwhile, finishing the step-up ends the channel instead of opening a second session") {
                seedRegisteredAccount()
                val channel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                authenticateViaSms(channel)
                val session = sessionOf(channel)
                post("/orchestrator/api/v1/channels/$channel/step-ups", """{"requiredAcr":"loa2"}""")
                // Only the session window lapses; the channel row is still reachable for this request.
                jdbcTemplate.update(
                    "UPDATE orchestrator.app_token_session SET refresh_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?",
                    appTokenSessionId(channel)
                )

                val gone = assertThrows<HttpClientErrorException> { authenticateViaPassword(channel) }

                gone.statusCode shouldBe HttpStatus.GONE
                stateOf(channel) shouldBe "EXPIRED"
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM orchestrator.app_token_session WHERE keycloak_session_id = ? AND access_token IS NULL",
                    Int::class.java, session
                ) shouldBe 1
            }
        }
    }
}
