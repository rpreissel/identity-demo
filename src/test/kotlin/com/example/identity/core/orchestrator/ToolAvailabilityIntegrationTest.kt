package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.tool.ToolAvailabilityService
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Tool availability (docs/03-tool-architektur.md): the client declares its supported toolIds at
 * channel creation, and the backend can switch a tool off at runtime. Both narrow
 * [com.example.identity.core.orchestrator.domain.journey.state.JourneyState.activatable] on every request.
 */
class ToolAvailabilityIntegrationTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var toolAvailabilityService: ToolAvailabilityService

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun adminAvailability(): List<Map<String, Any?>> = restTemplate.exchange(
        "http://localhost:$port/orchestrator/admin/tools/availability", org.springframework.http.HttpMethod.GET,
        org.springframework.http.HttpEntity<Void>(adminHeaders()),
        object : org.springframework.core.ParameterizedTypeReference<List<Map<String, Any?>>>() {}
    ).body!!

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.tools(): List<Map<String, Any?>> = this["tools"] as List<Map<String, Any?>>

    /** Every toolId a response currently points at or lists as an option, whichever applies. */
    @Suppress("UNCHECKED_CAST")
    private fun offeredToolIds(response: Map<String, Any?>): List<String> {
        val stepData = response["stepData"] as? Map<String, Any?>
        val options = (stepData?.get("options") as? List<String>).orEmpty()
        return options + listOfNotNull(response.nextRaw()["toolId"] as? String)
    }

    private fun smsEnabledIn(channel: String): Boolean? = adminAvailability()
        .first { it["channel"] == channel }.tools().first { it["toolId"] == "auth-sms" }["enabled"] as Boolean?

    init {
        given("an account with two active auth methods (sms + password) on a fresh channel") {
            `when`("the backend disables auth-sms mid-journey, while the client sits on the offer") {
                seedRegisteredAccount()
                // Same device, still linked: a fresh channel offers both. Options come only with the
                // create response; a later GET returns only `next`.
                val created = post("/orchestrator/api/v1/app/channels")
                val channelSessionId = created.channel()["channelSessionId"] as String

                // The stored state is untouched; activatable() filters live.
                toolAvailabilityService.disable("auth-sms", ChannelType.APP, "suspected compromise")

                val afterDisable = get("/orchestrator/api/v1/channels/$channelSessionId")
                val directActivation = runCatching { post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms") }
                val authenticated = authenticateViaPassword(channelSessionId)

                then("the fresh channel offered both") {
                    created.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    @Suppress("UNCHECKED_CAST")
                    created.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
                then("a plain GET without transition already drops the disabled one from the offer") {
                    afterDisable.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")
                }
                then("direct activation of the disabled tool is rejected too, not just omitted from the offer") {
                    shouldThrow<HttpClientErrorException> { directActivation.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
                then("the remaining candidate still works normally") {
                    authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                }
            }
        }

        given("an account whose only active auth methods are both backend-disabled") {
            `when`("a fresh entry journey computes its first offer") {
                seedRegisteredAccount()
                toolAvailabilityService.disable("auth-sms", ChannelType.APP, "maintenance")
                toolAvailabilityService.disable("auth-password", ChannelType.APP, "maintenance")

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val next = get("/orchestrator/api/v1/channels/$channelSessionId").next()

                then("the existing fallback to identification is reused, not a new dead end") {
                    next shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                }
            }
        }

        given("a client that declares only a subset of the catalog as available") {
            `when`("registering with availableTools restricted to ident-fsc, enroll-sms and confirm-email") {
                // Registration has mandatory steps (docs/04-orchestrierung.md #2). The set still covers
                // them, so availability narrows the offers without breaking the journey.
                val channelSessionId = post(
                    "/orchestrator/api/v1/app/channels",
                    """{"availableTools":["ident-fsc","enroll-sms","confirm-email"]}"""
                ).channel()["channelSessionId"] as String

                val identified = reIdentifyViaFsc(channelSessionId)
                confirmEmail(channelSessionId)
                val afterConfirm = get("/orchestrator/api/v1/channels/$channelSessionId")

                val enrollSmsToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (tan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val afterSms = patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$tan"}""")
                val final = get("/orchestrator/api/v1/channels/$channelSessionId")

                then("the address comes before any enrollment") {
                    identified.nextRaw()["toolId"] shouldBe "confirm-email"
                }
                then("enroll-password and enroll-device are never offered") {
                    offeredToolIds(afterConfirm) shouldNotContain "enroll-password"
                    offeredToolIds(afterConfirm) shouldNotContain "enroll-device"
                    offeredToolIds(afterSms) shouldNotContain "enroll-password"
                    offeredToolIds(afterSms) shouldNotContain "enroll-device"
                }
                then("the restricted path still completes") {
                    final.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                }
            }
        }

        given("an account with sms and password, on a channel whose client declares only auth-sms") {
            `when`("the client starts auth-password directly") {
                seedRegisteredAccount()
                val created = post("/orchestrator/api/v1/app/channels", """{"availableTools":["auth-sms"]}""")
                val channelSessionId = created.channel()["channelSessionId"] as String
                val directStart = runCatching { post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password") }

                then("it is never offered") {
                    offeredToolIds(created) shouldNotContain "auth-password"
                }
                then("the direct start is rejected as well (docs/05-api.md, availableTools)") {
                    shouldThrow<HttpClientErrorException> { directStart.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
            }
        }

        given("the public catalog and the admin availability endpoints") {
            `when`("listing the catalog, then toggling one tool off for the App channel only") {
                // The catalog is a JSON array, so the shared get() helper for objects doesn't fit.
                val catalogEntries = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/tools/catalog", org.springframework.http.HttpMethod.GET,
                    org.springframework.http.HttpEntity<Void>(headers()),
                    object : org.springframework.core.ParameterizedTypeReference<List<Map<String, Any?>>>() {}
                ).body!!
                val appEnabledBefore = smsEnabledIn("APP")
                val toggled = put("/orchestrator/admin/tools/auth-sms/availability/APP", """{"enabled":false,"reason":"test"}""")
                val appEnabledAfter = smsEnabledIn("APP")
                val webEnabledAfter = smsEnabledIn("WEB")

                then("the catalog lists the tool") {
                    catalogEntries.map { it["toolId"] } shouldContain "auth-sms"
                }
                then("the admin view reflects the toggle for App and leaves Web untouched") {
                    appEnabledBefore shouldBe true
                    toggled shouldBe HttpStatus.OK
                    appEnabledAfter shouldBe false
                    webEnabledAfter shouldBe true
                }
            }
        }

        given("an account with two auth methods, sms and password") {
            `when`("the operator ranks sms before password for the App channel") {
                seedRegisteredAccount()
                val ranked = put("/orchestrator/admin/tools/order/APP", """{"toolIds":["auth-sms","auth-password"]}""")

                @Suppress("UNCHECKED_CAST")
                val options = post("/orchestrator/api/v1/app/channels").stepData()["options"] as List<String>

                then("the App channel's selection follows that ranking instead of the default order") {
                    ranked shouldBe HttpStatus.OK
                    options shouldBe listOf("auth-sms", "auth-password")
                }
            }
        }

        given("a channel creation request without availableTools") {
            `when`("posting the raw request") {
                val result = runCatching {
                    restTemplate.exchange(
                        "http://localhost:$port/orchestrator/api/v1/app/channels", org.springframework.http.HttpMethod.POST,
                        org.springframework.http.HttpEntity("{}", headers()), mapType
                    )
                }

                then("it is rejected as a bad request") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }
        }
    }
}
