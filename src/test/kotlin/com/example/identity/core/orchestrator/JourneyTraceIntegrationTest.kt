package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.client.HttpClientErrorException
import java.time.Instant

/**
 * The per-step journey trace (docs/04-orchestrierung.md), distinct from the account's audit trail.
 * Read through the operator's views (`/orchestrator/admin/journey-trace/...`): one channel's entries
 * and the search over journeys.
 */
class JourneyTraceIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun adminGet(path: String): ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange("http://localhost:$port/orchestrator/admin/journey-trace$path", HttpMethod.GET, HttpEntity<Void>(adminHeaders()), mapType)

    @Suppress("UNCHECKED_CAST")
    private fun logOf(channelSessionId: String): List<Map<String, Any?>> =
        adminGet("/channel-sessions/$channelSessionId").body!!["entries"] as List<Map<String, Any?>>

    private fun search(query: String): Map<String, Any?> = adminGet("/journeys?$query").body!!

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.journeys(): List<Map<String, Any?>> = this["journeys"] as List<Map<String, Any?>>

    /** A registration, then a second journey on the same channel: the account deletion's confirmation. */
    private fun registerAndStartDeletion(): String {
        val channelSessionId = registerAndAuthenticate()
        post("/orchestrator/api/v1/channels/$channelSessionId/account-deletions")
        return channelSessionId
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.detail(): Map<String, Any?> = this["detail"] as Map<String, Any?>

    init {
        given("a channel that ran a full registration") {
            `when`("reading that channel's journey trace") {
                // A real run, not a seeded account: only an actual journey writes the trace.
                val channelSessionId = registerAndAuthenticate()

                val entries = logOf(channelSessionId)

                then("every entry belongs to the APP channel and names its intent") {
                    entries.shouldNotBeEmpty()
                    entries.all { it["channelType"] == "APP" } shouldBe true
                    entries.all { (it["intent"] as String).isNotBlank() } shouldBe true
                }
                then("the entries come oldest first, as the steps happened") {
                    val times = entries.map { Instant.parse(it["createdAt"] as String) }
                    times shouldBe times.sorted()
                }
                then("the journey's start, the tool's activation and its completion are recorded") {
                    entries.any { it["eventType"] == "Started" } shouldBe true
                    entries.any { it["eventType"] == "TOOL_ACTIVATED" && it.detail()["toolId"] == "ident-fsc" } shouldBe true
                    // In the version the client declared (ADR-51).
                    entries.any { it["eventType"] == "TOOL_ACTIVATED" && it.detail()["tool"] == "ident-fsc@1" } shouldBe true
                    entries.any { it["eventType"] == "Completed" && it.detail()["toolId"] == "ident-fsc" } shouldBe true
                }
                then("journeyState is a first-class field (like eventType), not tucked into detail") {
                    entries.any { it["eventType"] == "TOOL_ACTIVATED" && it["journeyState"] != null } shouldBe true
                    entries.none { it.detail().containsKey("state") } shouldBe true
                }
                then("the steps before the account was bound are attributed to that account too") {
                    val accountIds = entries.map { it["accountId"] }.toSet()
                    accountIds.size shouldBe 1
                    accountIds.single().shouldNotBeNull()
                    // "Started" is logged before ident-fsc binds the account - it still carries it.
                    entries.first { it["eventType"] == "Started" }["accountId"].shouldNotBeNull()
                }
            }
        }

        given("a tool run that fails once") {
            `when`("reading the journey trace afterwards") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/tools/api/ident-fsc/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
                patch(
                    "/tools/api/ident-fsc/v1/$identToolSessionId",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"WRONGCODE"}"""
                )

                val entries = logOf(channelSessionId)

                then("the failed attempt shows up as its own entry") {
                    entries.any { it["eventType"] == "TOOL_FAILED" } shouldBe true
                }
            }
        }

        given("an AUTHENTICATED channel with no journey currently running") {
            `when`("logging out") {
                // A real run, not a seeded account: only an actual journey writes the trace.
                val channelSessionId = registerAndAuthenticate()
                deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId")

                val entries = logOf(channelSessionId)

                then("the logout itself still shows up in the journey trace") {
                    entries.single { it["eventType"] == "LOGGED_OUT" }["channelType"] shouldBe "APP"
                }
            }
        }

        given("a channel that registered and then started an account deletion") {
            `when`("searching the journeys of that channel") {
                val channelSessionId = registerAndStartDeletion()
                val journeys = search("channelSessionId=$channelSessionId").journeys()
                val entries = logOf(channelSessionId)

                then("each journey is one line, newest start first, with its steps counted") {
                    journeys.first()["intent"] shouldBe "DELETE_ACCOUNT"
                    journeys.forEach { journey ->
                        journey["stepCount"] shouldBe entries.count { it["journeyId"] == journey["journeyId"] }
                    }
                }
                then("the registration reads as succeeded and is attributed to its account") {
                    val registration = journeys.last { it["parentJourneyId"] == null }
                    registration["outcome"] shouldBe "SUCCEEDED"
                    registration["accountId"].shouldNotBeNull()
                }
            }

            `when`("paging one journey at a time") {
                val channelSessionId = registerAndStartDeletion()
                val first = search("channelSessionId=$channelSessionId&limit=1")
                val second = search("channelSessionId=$channelSessionId&limit=1&cursor=${first["nextCursor"]}")

                then("the second page continues where the first stopped") {
                    first.journeys().single()["intent"] shouldBe "DELETE_ACCOUNT"
                    first["nextCursor"].shouldNotBeNull()
                    second.journeys().single()["journeyId"] shouldNotBe first.journeys().single()["journeyId"]
                }
            }

            `when`("filtering by intent across all channels") {
                registerAndStartDeletion()
                val journeys = search("intent=DELETE_ACCOUNT").journeys()

                then("only journeys of that intent come back") {
                    journeys.shouldNotBeEmpty()
                    journeys.all { it["intent"] == "DELETE_ACCOUNT" } shouldBe true
                }
            }

            `when`("searching by the account the channel resolved") {
                val channelSessionId = registerAndStartDeletion()
                val accountId = logOf(channelSessionId).first()["accountId"]
                val journeys = search("accountId=$accountId").journeys()

                then("the channel's journeys are found, also those started before the account was known") {
                    journeys.map { it["channelSessionId"] }.toSet() shouldBe setOf(channelSessionId)
                    journeys.size shouldBe search("channelSessionId=$channelSessionId").journeys().size
                }
            }
        }

        given("a cursor this service did not write") {
            `when`("searching with it") {
                val refused = runCatching { adminGet("/journeys?cursor=nonsense") }

                then("the search is refused as bad input") {
                    shouldThrow<HttpClientErrorException> { refused.getOrThrow() }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }
        }
    }
}
