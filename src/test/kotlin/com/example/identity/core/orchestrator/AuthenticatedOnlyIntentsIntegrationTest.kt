package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * Which intents a channel may start before it is logged in (docs/04-orchestrierung.md, intent
 * table): STEP_UP, MANAGE_AUTH_METHODS, DELETE_ACCOUNT and LOGOUT only on an AUTHENTICATED channel,
 * RE_IDENTIFY never directly. Ended channels are [CancelLogoutIntegrationTest]'s subject.
 */
class AuthenticatedOnlyIntentsIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    /** A fresh channel on an unlinked device: its entry journey runs, nobody is logged in. */
    private fun anonymousChannel(): String =
        post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

    private fun journeysOf(channelSessionId: String): List<Pair<String, String>> =
        jdbcTemplate.queryForList(
            "SELECT intent, lifecycle FROM orchestrator.auth_journey WHERE channel_session_id = ? ORDER BY created_at, id",
            UUID.fromString(channelSessionId)
        ).map { it["intent"] as String to it["lifecycle"] as String }

    private fun channelCount(): Int =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orchestrator.channel_session", Int::class.java)!!

    init {
        // Each request starts its intent on an AUTHENTICATED channel; on an anonymous one it is refused.
        listOf(
            Triple("method management", "MANAGE_AUTH_METHODS") { id: String -> post("/orchestrator/api/v1/channels/$id/enrollments") },
            Triple("removing a method", "MANAGE_AUTH_METHODS") { id: String -> delete("/orchestrator/api/v1/channels/$id/methods/sms-1") },
            Triple("retracting the email address", "MANAGE_AUTH_METHODS") { id: String -> delete("/orchestrator/api/v1/channels/$id/attributes/email") },
            Triple("an account deletion", "DELETE_ACCOUNT") { id: String -> post("/orchestrator/api/v1/channels/$id/account-deletions") },
            Triple("a logout", "LOGOUT") { id: String -> post("/orchestrator/api/v1/channels/$id/logouts") },
        ).forEach { (move, intent, request) ->
            given("an anonymous channel whose entry journey is running") {
                `when`("$move is requested") {
                    val channelSessionId = anonymousChannel()
                    val before = journeysOf(channelSessionId)

                    val result = runCatching { request(channelSessionId) }

                    then("it is refused with 409") {
                        shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                    }
                    then("no $intent journey starts and the running entry journey is left as it was") {
                        journeysOf(channelSessionId) shouldBe before
                    }
                }
            }
        }

        given("an anonymous channel whose entry journey is running") {
            `when`("a step-up to loa3 is requested") {
                val channelSessionId = anonymousChannel()
                val before = journeysOf(channelSessionId)

                val response = post("/orchestrator/api/v1/channels/$channelSessionId/step-ups", """{"requiredAcr":"loa3"}""")

                then("no STEP_UP journey starts - the running login has to reach the raised floor itself") {
                    journeysOf(channelSessionId) shouldBe before
                }
                then("the channel stays logged out") {
                    response.channel()["state"] shouldNotBe "AUTHENTICATED"
                }
            }
        }

        // Only entry intents open a channel; RE_IDENTIFY is reached only through RequireSubJourney.
        listOf("step_up", "manage_auth_methods", "delete_account", "logout", "re_identify").forEach { intent ->
            given("a device without a channel") {
                `when`("a channel is opened with intent=$intent") {
                    val result = runCatching { post("/orchestrator/api/v1/app/channels", """{"intent":"$intent"}""") }

                    then("it is refused with 409") {
                        shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                    }
                    then("no channel and no journey are created") {
                        channelCount() shouldBe 0
                        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orchestrator.auth_journey", Int::class.java) shouldBe 0
                    }
                }
            }
        }
    }
}
