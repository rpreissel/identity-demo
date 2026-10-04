package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * Cancelling an in-progress process versus logging out of a finished one. Shared plumbing lives in
 * IntegrationTestSupport.
 */
class CancelLogoutIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun stateOf(channelSessionId: String): String = jdbcTemplate.queryForObject(
        "SELECT state FROM orchestrator.channel_session WHERE id = ?", String::class.java, UUID.fromString(channelSessionId)
    )!!

    private fun appTokenSessionOf(channelSessionId: String): UUID = jdbcTemplate.queryForObject(
        "SELECT app_token_session_id FROM orchestrator.channel_session WHERE id = ?", UUID::class.java,
        UUID.fromString(channelSessionId)
    )!!

    /** Lets the channel's refresh window lapse, as if the app had been idle. */
    private fun lapseRefreshWindow(channelSessionId: String) {
        jdbcTemplate.update(
            "UPDATE orchestrator.app_token_session SET access_expires_at = DATEADD('SECOND', -10, CURRENT_TIMESTAMP), " +
                "refresh_expires_at = DATEADD('SECOND', -1, CURRENT_TIMESTAMP) WHERE id = ?",
            appTokenSessionOf(channelSessionId)
        )
    }

    /** Runs ident-fsc to Identified on a fresh channel; returns the channel and the finished tool session. */
    private fun identifiedChannel(): Pair<String, String> {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val identToolSessionId = post("/tools/api/ident-fsc/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
        patch(
            "/tools/api/ident-fsc/v1/$identToolSessionId",
            """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
        )
        return channelSessionId to identToolSessionId
    }

    /** A channel that ended in [finalState]: logged out directly, or expired after its refresh window lapsed. */
    private fun endedChannel(finalState: String): String {
        val channelSessionId = loginAsSeededAccount()
        if (finalState == "LOGGED_OUT") {
            deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId")
        } else {
            get("/orchestrator/api/v1/channels/$channelSessionId/token")
            lapseRefreshWindow(channelSessionId)
            runCatching { get("/orchestrator/api/v1/channels/$channelSessionId/token") }
        }
        return channelSessionId
    }

    init {
        given("a channel identified mid-registration") {
            `when`("the journey is cancelled and the finished ident-fsc tool session is used again") {
                // Identified (account created) before cancelling, to prove the channel doesn't stay
                // half-bound to that account afterwards.
                val (channelSessionId, identToolSessionId) = identifiedChannel()

                val cancelled = delete("/orchestrator/api/v1/channels/$channelSessionId/journey")
                val reused = runCatching { patch("/tools/api/ident-fsc/v1/$identToolSessionId", """{"fsc":"VALIDCODE"}""") }

                then("the account being set up goes with the cancel, as a whole (ADR-46)") {
                    cancelled.channel()["state"] shouldBe "ANONYMOUS"
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.account", Int::class.java) shouldBe 0
                }
                then("a fresh registration is offered at once") {
                    cancelled.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                    // shouldContainAll, not exact: identification is offered, not the catalog's exact set.
                    @Suppress("UNCHECKED_CAST")
                    (cancelled.stepData()["options"] as List<String>) shouldContainAll listOf("ident-fsc", "ident-eid")
                }
                then("the old ident-fsc tool session is gone: it ended the moment it completed") {
                    shouldThrow<HttpClientErrorException> { reused.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }

            `when`("the channel is logged out directly and a new channel is opened") {
                val (channelSessionId, identToolSessionId) = identifiedChannel()

                // Direct DELETE logs out without confirmation (non-authenticated channel).
                val logout = deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId")
                val reused = runCatching { patch("/tools/api/ident-fsc/v1/$identToolSessionId", """{"fsc":"VALIDCODE"}""") }
                val newChannel = post("/orchestrator/api/v1/app/channels")

                then("the logout goes through") {
                    logout shouldBe HttpStatus.NO_CONTENT
                }
                then("the registration is cancelled too: the old tool session is gone") {
                    shouldThrow<HttpClientErrorException> { reused.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
                then("half-registered is not a returning user, so the new channel starts registration again") {
                    // Same rule as plain Cancel, docs/06-ablaeufe.md.
                    newChannel.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                }
            }
        }

        given("a registered account with sms and password, bound to this device") {
            `when`("a login is cancelled mid-way") {
                seedRegisteredAccount()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                post("/tools/api/auth-sms/v1?channel=$channelSessionId")

                val cancelled = delete("/orchestrator/api/v1/channels/$channelSessionId/journey")

                then("a fresh login attempt is offered") {
                    // LOGIN cancel does not change the channel state (only REGISTERING/STEP_UP do).
                    // Two active methods (sms, password) mean a selection page.
                    cancelled.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    @Suppress("UNCHECKED_CAST")
                    (cancelled.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
            }
        }

        given("an authenticated channel") {
            `when`("the user confirms an interactive logout and then opens a new channel") {
                val channelSessionId = loginAsSeededAccount()
                val logoutPrompt = post("/orchestrator/api/v1/channels/$channelSessionId/logouts")
                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                val loggedOutChannel = get("/orchestrator/api/v1/channels/$channelSessionId")

                val newChannel = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
                val authenticated = authenticateViaSms(newChannelSessionId)
                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")

                then("the logout asks for confirmation first, the channel still authenticated") {
                    logoutPrompt.channel()["state"] shouldBe "AUTHENTICATED"
                    logoutPrompt.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                }
                then("the old channel stays LOGGED_OUT with no next step and is never re-derived") {
                    loggedOutChannel.channel()["state"] shouldBe "LOGGED_OUT"
                    loggedOutChannel["next"].shouldBeNull()
                    loggedOutChannel.channel()["currentAcr"].shouldBeNull()
                }
                then("the new channel recognizes the account via the device link and offers both login methods") {
                    newChannelSessionId shouldNotBe channelSessionId
                    // Two active methods (sms, password) mean a selection page.
                    newChannel.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    @Suppress("UNCHECKED_CAST")
                    (newChannel.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
                then("the new login succeeds") {
                    authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    afterLogin.channel()["state"] shouldBe "AUTHENTICATED"
                }
            }

            `when`("it is logged out directly (DELETE)") {
                val channelSessionId = loginAsSeededAccount()
                get("/orchestrator/api/v1/channels/$channelSessionId/token")
                val appTokenSessionId = appTokenSessionOf(channelSessionId)

                val logout = deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId")

                then("the logout goes through") {
                    logout shouldBe HttpStatus.NO_CONTENT
                }
                then("its RefreshToken is discarded as well, the same way as the confirmed logout") {
                    jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM orchestrator.app_token_session WHERE id = ? AND refresh_token IS NULL",
                        Int::class.java, appTokenSessionId
                    ) shouldBe 1
                }
            }
        }

        given("an authenticated channel whose refresh window has lapsed (idle)") {
            `when`("the app asks for a token") {
                val channelSessionId = loginAsSeededAccount()
                get("/orchestrator/api/v1/channels/$channelSessionId/token")
                val appTokenSessionId = appTokenSessionOf(channelSessionId)
                lapseRefreshWindow(channelSessionId)

                val result = runCatching { get("/orchestrator/api/v1/channels/$channelSessionId/token") }

                then("the login is over: 410") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.GONE
                }
                then("the channel ends as EXPIRED") {
                    stateOf(channelSessionId) shouldBe "EXPIRED"
                }
                then("its tokens are discarded") {
                    jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM orchestrator.app_token_session WHERE id = ? AND refresh_token IS NULL AND access_token IS NULL",
                        Int::class.java, appTokenSessionId
                    ) shouldBe 1
                }
            }
        }

        // docs/invarianten.md I-1: a channel that has ended never moves again. One check refuses every
        // move, whichever way the channel ended, so all endpoints run against LOGGED_OUT and one
        // against EXPIRED. ModelBasedJourneyTest checks I-1 across random sequences.
        val allMoves = listOf(
            "a step-up" to ({ id: String -> "/orchestrator/api/v1/channels/$id/step-ups" } to """{"requiredAcr":"loa3"}"""),
            "method management" to ({ id: String -> "/orchestrator/api/v1/channels/$id/enrollments" } to "{}"),
            "a peer login" to ({ id: String -> "/orchestrator/api/v1/channels/$id/peer-logins" } to "{}"),
            "an account deletion" to ({ id: String -> "/orchestrator/api/v1/channels/$id/account-deletions" } to "{}"),
            "a logout" to ({ id: String -> "/orchestrator/api/v1/channels/$id/logouts" } to "{}"),
            "a tool" to ({ id: String -> "/tools/api/auth-sms/v1?channel=$id" } to "{}"),
        )
        mapOf("LOGGED_OUT" to allMoves, "EXPIRED" to allMoves.takeLast(1)).forEach { (finalState, moves) ->
            given("a channel that has ended as $finalState") {
                `when`("it is read") {
                    val channelSessionId = endedChannel(finalState)

                    val read = get("/orchestrator/api/v1/channels/$channelSessionId")

                    then("it reports its final state and no next step") {
                        read.channel()["state"] shouldBe finalState
                        read["next"].shouldBeNull()
                    }
                }

                moves.forEach { (move, request) ->
                    val (urlOf, body) = request
                    `when`("$move is requested") {
                        val channelSessionId = endedChannel(finalState)

                        val result = runCatching { post(urlOf(channelSessionId), body) }

                        then("it is refused and the channel stays $finalState, with no journey running") {
                            shouldThrow<HttpClientErrorException> { result.getOrThrow() }
                            stateOf(channelSessionId) shouldBe finalState
                            jdbcTemplate.queryForObject(
                                "SELECT COUNT(*) FROM orchestrator.auth_journey WHERE channel_session_id = ? AND lifecycle = 'STARTED'",
                                Int::class.java, UUID.fromString(channelSessionId)
                            ) shouldBe 0
                        }
                    }
                }
            }
        }

        given("an sms user who just authenticated") {
            `when`("the completed auth-sms PATCH is replayed on the still-open channel") {
                registerWithSmsOnly()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val (tan, activation) = captureMockTan {
                    post("/tools/api/auth-sms/v1?channel=$channelSessionId")
                }
                val toolSessionId = activation.nextRaw()["toolSessionId"] as String
                patch("/tools/api/auth-sms/v1/$toolSessionId", """{"tan":"$tan"}""")

                val replay = runCatching { patch("/tools/api/auth-sms/v1/$toolSessionId", """{"tan":"$tan"}""") }

                then("the finished tool session is no longer usable") {
                    shouldThrow<HttpClientErrorException> { replay.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }
        }
    }
}
