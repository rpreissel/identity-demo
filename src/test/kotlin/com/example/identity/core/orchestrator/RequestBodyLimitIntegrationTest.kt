package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * A body is capped before anything parses it: Spring reads it before a handler checks the caller,
 * and a DPoP key costs nothing (RequestBodyLimitFilter, docs/review-2026-10-03-sicherheitsaudit.md SA-10).
 */
class RequestBodyLimitIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun send(method: HttpMethod, path: String, body: String): Result<Any?> = runCatching {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        restTemplate.exchange("http://localhost:$port$path", method, HttpEntity(body, headers), String::class.java)
    }

    init {
        given("a body of one megabyte") {
            val huge = """{"availableTools":["${"x".repeat(1_000_000)}"]}"""

            `when`("it goes to the Keycloak upsert without any assertion and to the App channel creation") {
                val keycloak = send(HttpMethod.PATCH, "/orchestrator/api/v1/kc/channels/${UUID.randomUUID()}", huge)
                val app = send(HttpMethod.POST, "/orchestrator/api/v1/app/channels", huge)

                then("both are refused with 413 before the body is read") {
                    listOf(keycloak, app).forEach {
                        shouldThrow<HttpClientErrorException> { it.getOrThrow() }.statusCode.value() shouldBe 413
                    }
                }
            }
        }
    }
}
