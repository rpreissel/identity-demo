package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.keycloak.PeerAuthAssertion
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Covers the stateless, Channel/ToolSession-free `mgmt` endpoints backing Keycloak's native
 * password credential (`OrchestratorPasswordStorageProvider`).
 */
class MgmtPasswordIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun stubAssertion(accountBinding: String) {
        every { peerAuthValidator.validate(any(), any(), any(), any()) } returns PeerAuthAssertion(
            jti = UUID.randomUUID().toString(),
            issuedAt = Instant.now(),
            channelBinding = accountBinding,
            subject = null
        )
    }

    private fun accountIdFor(email: String): Long =
        jdbcTemplate.queryForObject("SELECT account_id FROM account.anchor WHERE attribute_type = 'email' AND normalized_value = ?", Long::class.java, email)!!

    private fun mgmtPost(path: String, body: String): org.springframework.http.ResponseEntity<Map<String, Any?>> =
        restTemplate.exchange(
            "http://localhost:$port$path",
            HttpMethod.POST,
            HttpEntity(
                body,
                HttpHeaders().apply {
                    set("Authorization", "Bearer mock-peer-auth-token")
                    set("Content-Type", "application/json")
                }
            ),
            mapType
        )

    init {
        given("an app channel with a valid DPoP proof") {
            `when`("it calls the Keycloak-only endpoint, naming an account") {
                val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                val accountId = accountIdFor(email)

                val result = runCatching {
                    restTemplate.exchange(
                        "http://localhost:$port/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                        HttpMethod.POST,
                        HttpEntity("""{"password":"correct-horse-battery"}""", headers()),
                        mapType
                    )
                }

                then("the endpoint refuses it with 401, whatever account it names") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.UNAUTHORIZED
                }
            }
        }

        given("a mismatched peer-auth binding") {
            `when`("mgmt-verify's channel_binding claim doesn't match the accountId in the path") {
                val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                val accountId = accountIdFor(email)
                stubAssertion(accountBinding = "some-other-binding")

                val result = runCatching {
                    mgmtPost("/orchestrator/api/v1/tools/auth-password/mgmt/$accountId", """{"password":"correct-horse-battery"}""")
                }

                then("it is rejected as unauthorized (same contract as a missing/invalid peer-auth assertion)") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.UNAUTHORIZED
                }
            }
        }
    }
}
