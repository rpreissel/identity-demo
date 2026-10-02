package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.channel.KeycloakChannelService
import com.nimbusds.jwt.PlainJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * ADR-43 in the default profile, where the mock token service plays Keycloak's role: reaching
 * AUTHENTICATED opens the session, the channel lives exactly as long as the session window, and a
 * sign-out Keycloak reports ends the App channel whose login held that session.
 */
class AppTokenIssuerIntegrationTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var keycloakChannelService: KeycloakChannelService

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    /** ChannelService's fixed lifetime of a channel that has not authenticated yet. */
    private val channelLifetime = Duration.ofHours(24)

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

    private fun keycloakSessionOf(channel: String): String = jdbcTemplate.queryForObject(
        "SELECT keycloak_session_id FROM orchestrator.app_token_session WHERE id = ?", String::class.java, appTokenSessionId(channel)
    )!!

    private fun tokenAcr(channel: String): String =
        PlainJWT.parse(get("/orchestrator/api/v1/channels/$channel/token")["accessToken"] as String).jwtClaimsSet.getStringClaim("acr")

    /** A fresh loa2 login of the seeded account (sms + password). */
    private fun loginAtLoa2(): String {
        val channel = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
        authenticateViaSms(channel)
        authenticateViaPassword(channel)
        return channel
    }

    /** Seeds a registered account and signs in on a default (loa1) channel via sms alone. */
    private fun signInViaSmsAlone(): String {
        seedRegisteredAccount()
        val channel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        authenticateViaSms(channel)
        return channel
    }

    init {
        given("an App channel before authentication") {
            `when`("it is created") {
                seedRegisteredAccount()
                val channel = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String

                then("the fixed channel lifetime applies") {
                    val remaining = Duration.between(Instant.now(), channelExpiresAt(channel))
                    (channelLifetime - remaining) shouldBeLessThan Duration.ofMinutes(1)
                }
            }
        }

        given("an App channel of a registered account") {
            `when`("it reaches AUTHENTICATED") {
                val channel = loginAsSeededAccount()

                then("the session is opened in the same transition, before any token request, and the channel lives as long as its window") {
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
        }

        given("an authenticated App channel whose access token has expired") {
            `when`("the token is refreshed") {
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

                then("the refresh moves the channel's expiry to the new session window") {
                    val window = refreshExpiresAt(context).shouldNotBeNull()
                    Duration.between(Instant.now(), window).toMinutes() shouldBe 29L
                    channelExpiresAt(channel) shouldBe window
                }
            }
        }

        given("an authenticated App channel whose session window has passed") {
            `when`("the channel and its token are requested") {
                val channel = loginAsSeededAccount()
                jdbcTemplate.update(
                    "UPDATE orchestrator.channel_session SET expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?",
                    UUID.fromString(channel)
                )

                val channelResult = runCatching { get("/orchestrator/api/v1/channels/$channel") }
                val tokenResult = runCatching { get("/orchestrator/api/v1/channels/$channel/token") }

                then("the channel is refused like any expired one") {
                    shouldThrow<HttpClientErrorException> { channelResult.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                    shouldThrow<HttpClientErrorException> { tokenResult.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }
        }

        given("two App logins of the same account, each holding its own Keycloak session") {
            `when`("Keycloak reports the sign-out of the first one's session") {
                val accountId = seedRegisteredAccount()
                val signedOut = loginAtLoa2()
                val other = loginAtLoa2()
                // The mock provider has no `sid`; under `keycloak` KeycloakTokenProvider records it from the token.
                jdbcTemplate.update("UPDATE orchestrator.app_token_session SET keycloak_session_id = 'kc-session-1' WHERE id = ?", appTokenSessionId(signedOut))
                jdbcTemplate.update("UPDATE orchestrator.app_token_session SET keycloak_session_id = 'kc-session-2' WHERE id = ?", appTokenSessionId(other))

                keycloakChannelService.signedOutAtKeycloak(Subject.Account(accountId), "kc-session-1")
                val tokenAfterSignOut = runCatching { get("/orchestrator/api/v1/channels/$signedOut/token") }

                then("that App channel ends and hands out no token") {
                    stateOf(signedOut) shouldBe "LOGGED_OUT"
                    shouldThrow<HttpClientErrorException> { tokenAfterSignOut.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
                then("the other login of the same account does not end") {
                    stateOf(other) shouldBe "AUTHENTICATED"
                }
            }
        }

        given("an App channel signed in at loa1 via sms") {
            `when`("its token is requested") {
                val channel = signInViaSmsAlone()
                val acr = tokenAcr(channel)

                then("the token carries loa1") {
                    acr shouldBe "loa1"
                }
            }

            `when`("a step-up to loa2 completes") {
                val channel = signInViaSmsAlone()
                val session = keycloakSessionOf(channel)
                post("/orchestrator/api/v1/channels/$channel/step-ups", """{"requiredAcr":"loa2"}""")
                val completed = authenticateViaPassword(channel)
                val acr = tokenAcr(channel)

                then("the channel is back at AUTHENTICATED") {
                    completed.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    stateOf(channel) shouldBe "AUTHENTICATED"
                }
                then("the transition re-mints the token with the raised acr, in the same session") {
                    val cached = jdbcTemplate.queryForObject(
                        "SELECT access_token FROM orchestrator.app_token_session WHERE id = ?", String::class.java, appTokenSessionId(channel)
                    )
                    PlainJWT.parse(cached).jwtClaimsSet.getStringClaim("acr") shouldBe "loa2"
                    acr shouldBe "loa2"
                    keycloakSessionOf(channel) shouldBe session
                }
            }

            `when`("the session window lapses during the step-up and the step-up then completes") {
                val channel = signInViaSmsAlone()
                val session = keycloakSessionOf(channel)
                post("/orchestrator/api/v1/channels/$channel/step-ups", """{"requiredAcr":"loa2"}""")
                // Only the session window lapses; the channel row is still reachable for this request.
                jdbcTemplate.update(
                    "UPDATE orchestrator.app_token_session SET refresh_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?",
                    appTokenSessionId(channel)
                )

                val result = runCatching { authenticateViaPassword(channel) }

                then("the request is refused with 410") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.GONE
                }
                then("the channel ends instead of opening a second session") {
                    stateOf(channel) shouldBe "EXPIRED"
                    jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM orchestrator.app_token_session WHERE keycloak_session_id = ? AND access_token IS NULL",
                        Int::class.java, session
                    ) shouldBe 1
                }
            }
        }
    }
}
