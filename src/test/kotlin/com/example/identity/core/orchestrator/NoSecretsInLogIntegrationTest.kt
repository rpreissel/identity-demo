package com.example.identity.core.orchestrator

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import org.springframework.web.client.HttpClientErrorException

/**
 * I-19: no code and no recipient reaches the log - not through `println`, which ArchUnit forbids,
 * and not through a logger either. Runs the paths that send and check codes, plus a rejected input,
 * with every log event of the application captured.
 */
class NoSecretsInLogIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeScenario { stubDpopWithFakeJwk(jwkThumbprintService) }

        given("a registration with address and SMS, and a lookup login with a mistyped address") {
            val appender = ListAppender<ILoggingEvent>()
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

            `when`("the codes are sent, entered, and the mistyped address is rejected") {
                appender.start()
                root.addAppender(appender)
                val mistyped = "max.mustermann@@example.com"
                val (email, rejected) = try {
                    val channelSessionId = identify()
                    val email = confirmEmail(channelSessionId)
                    enrollSms(channelSessionId)

                    val lookup = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                        .channel()["channelSessionId"] as String
                    val toolSessionId = post("/orchestrator/api/v1/channels/$lookup/tools/auth-sms-lookup")
                        .nextRaw()["toolSessionId"] as String
                    val rejected = runCatching {
                        patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms-lookup", """{"email":"$mistyped"}""")
                    }
                    email to rejected
                } finally {
                    root.detachAppender(appender)
                    appender.stop()
                }
                // As whole words: a six-digit code may occur by chance inside an id.
                val codes = (smsGateway.outbox().map { it.tan } + mailServer.outbox().map { it.code })
                    .map { Regex("""\b$it\b""") }
                val values = listOf(email, mistyped, "+491701234567", "170 1234567").map { Regex(Regex.escape(it)) }
                val secrets = codes + values
                val lines = appender.list.map { event ->
                    event.formattedMessage + (event.throwableProxy?.let { " " + it.message } ?: "")
                }

                then("the mistyped address is refused with 400") {
                    shouldThrow<HttpClientErrorException> { rejected.getOrThrow() }.statusCode.value() shouldBe 400
                }
                then("no log line carries a code, an address or a number") {
                    lines.filter { line -> secrets.any { it.containsMatchIn(line) } }.shouldBeEmpty()
                }
            }
        }
    }
}
