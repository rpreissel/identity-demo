package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * A client declares which tools it can render, each in the one version it speaks
 * (`availableTools`, ADR-51). The channel stores only what the server serves: free strings from a
 * cheap DPoP key never reach the table, and a call in another version than the declared one fails.
 */
class ChannelToolDeclarationIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun storedTools(channelSessionId: String): String = jdbcTemplate.queryForObject(
        "select cast(available_tools as varchar) from orchestrator.channel_session where id = ?",
        String::class.java, UUID.fromString(channelSessionId)
    )!!

    private fun createChannel(vararg tools: String): Result<Map<String, Any?>> = runCatching {
        post("/orchestrator/api/v1/app/channels", """{"availableTools":[${tools.joinToString(",") { "\"$it\"" }}]}""")
    }

    init {
        given("an App client declaring a served tool, an unknown one and a version the server does not serve") {
            lateinit var stored: String
            `when`("it opens a channel") {
                val channelSessionId = createChannel("ident-fsc@1", "made-up-tool@1", "enroll-sms@9").getOrThrow()
                    .channel()["channelSessionId"] as String
                stored = storedTools(channelSessionId)
            }
            then("the channel keeps the served tool in its version and drops the rest") {
                stored shouldContain "ident-fsc@1"
                stored shouldNotContain "made-up-tool"
                stored shouldNotContain "enroll-sms"
            }
        }

        mapOf(
            "a tool without its version" to arrayOf("ident-fsc"),
            "a malformed entry" to arrayOf("x".repeat(500) + "<script>"),
            "one tool in two versions" to arrayOf("ident-fsc@1", "ident-fsc@2"),
        ).forEach { (case, tools) ->
            given("an App client declaring $case") {
                var result: Result<Map<String, Any?>> = Result.success(emptyMap())
                `when`("it opens a channel") {
                    result = createChannel(*tools)
                }
                then("it is rejected as a bad request") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }
        }

        given("a channel that declared ident-fsc in version 1") {
            var other: Result<Map<String, Any?>> = Result.success(emptyMap())
            lateinit var declared: Map<String, Any?>
            `when`("the client starts the tool in version 2, then in version 1") {
                val channelSessionId = createChannel("ident-fsc@1").getOrThrow().channel()["channelSessionId"] as String
                other = runCatching { post("/tools/api/ident-fsc/v2?channel=$channelSessionId") }
                declared = post("/tools/api/ident-fsc/v1?channel=$channelSessionId")
            }
            then("another version has no route") {
                shouldThrow<HttpClientErrorException> { other.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
            }
            then("the declared version starts") {
                declared.next()["toolId"] shouldBe "ident-fsc"
            }
        }
    }
}
