package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Two versions of one tool side by side (ADR-51): `enroll-sms@2` asks for the consent with the
 * number, `enroll-sms@1` goes without. Each channel speaks the version it declared, and the change
 * log tells afterwards which one set the number up.
 */
class EnrollSmsVersionsIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun registeringChannel(enrollSms: String): String {
        val tools = toolRegistry.tools().map { it.toolId.value }.filter { it != "enroll-sms" } + enrollSms
        return identifyAndConfirmEmail(availableTools = tools)
    }

    private fun methodAddedDetails(channelSessionId: String): String = jdbcTemplate.queryForObject(
        """
        SELECT CAST(e.details AS VARCHAR) FROM account.change_log e
        WHERE e.change_type = 'METHOD_ADDED' AND e.subject = 'sms'
          AND e.account_id = (SELECT account_id FROM orchestrator.channel_session WHERE id = CAST(? AS UUID))
        """.trimIndent(),
        String::class.java, channelSessionId
    )!!

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.missingFields(): List<String> = (this["stepData"] as Map<String, Any?>)["missingFields"] as List<String>

    init {
        given("a channel that declared enroll-sms@2") {
            var otherVersion: Result<Map<String, Any?>> = Result.success(emptyMap())
            lateinit var started: Map<String, Any?>
            lateinit var withoutConsent: Map<String, Any?>
            lateinit var withConsent: Map<String, Any?>
            var smsWithoutConsent = 0
            lateinit var audit: String
            `when`("it sets up a number: first without, then with the consent") {
                val channelSessionId = registeringChannel("enroll-sms@2")
                otherVersion = runCatching { post("/tools/api/enroll-sms/v1?channel=$channelSessionId") }
                started = post("/tools/api/enroll-sms/v2?channel=$channelSessionId")
                val toolSessionId = started.nextRaw()["toolSessionId"] as String
                val before = smsGateway.outbox().size
                withoutConsent = patch("/tools/api/enroll-sms/v2/$toolSessionId", """{"phoneNumber":"+49 170 1234567"}""")
                smsWithoutConsent = smsGateway.outbox().size - before
                val (tan, response) = captureMockTan {
                    patch("/tools/api/enroll-sms/v2/$toolSessionId", """{"phoneNumber":"+49 170 1234567","consent":true}""")
                }
                withConsent = response
                patch("/tools/api/enroll-sms/v2/$toolSessionId", """{"tan":"$tan"}""")
                audit = methodAddedDetails(channelSessionId)
            }
            then("version 1 is refused on this channel") {
                shouldThrow<HttpClientErrorException> { otherVersion.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
            }
            then("the first step asks for the number and the consent") {
                started.missingFields() shouldBe listOf("phoneNumber", "consent")
            }
            then("without the consent no SMS goes out and the consent is still missing") {
                smsWithoutConsent shouldBe 0
                withoutConsent.missingFields() shouldBe listOf("phoneNumber", "consent")
            }
            then("with the consent the TAN step follows") {
                withConsent.nextRaw()["step"] shouldBe "tanInput"
            }
            then("the change log names version 2") {
                audit shouldContain "\"tool\":\"enroll-sms@2\""
            }
        }

        given("a channel that declared enroll-sms@1, as the app does") {
            lateinit var started: Map<String, Any?>
            lateinit var audit: String
            `when`("it sets up a number without any consent") {
                val channelSessionId = registeringChannel("enroll-sms@1")
                started = post("/tools/api/enroll-sms/v1?channel=$channelSessionId")
                val toolSessionId = started.nextRaw()["toolSessionId"] as String
                val (tan, _) = captureMockTan {
                    patch("/tools/api/enroll-sms/v1/$toolSessionId", """{"phoneNumber":"+49 170 1234567"}""")
                }
                patch("/tools/api/enroll-sms/v1/$toolSessionId", """{"tan":"$tan"}""")
                audit = methodAddedDetails(channelSessionId)
            }
            then("the first step asks for the number alone") {
                started.missingFields() shouldBe listOf("phoneNumber")
            }
            then("the change log names version 1") {
                audit shouldContain "\"tool\":\"enroll-sms@1\""
            }
        }
    }
}
