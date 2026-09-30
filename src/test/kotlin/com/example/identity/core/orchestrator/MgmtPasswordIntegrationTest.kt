package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.example.identity.core.orchestrator.kc.PeerAuthAssertion
import com.example.identity.core.orchestrator.kc.PeerAuthValidator
import com.ninjasquad.springmockk.MockkBean
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

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    override val resetPerWhen = true

    init {
        beforeScenario { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun stubAssertion(accountAnchor: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns PeerAuthAssertion(
            jti = UUID.randomUUID().toString(),
            issuedAt = Instant.now(),
            channelAnchor = accountAnchor,
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

        given("an account with an enrolled password") {
            `when`("mgmt-verify is called with the correct password, anchored to that accountId") {
                val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                val accountId = accountIdFor(email)
                stubAssertion(accountAnchor = accountId.toString())

                val response = mgmtPost(
                    "/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                    """{"password":"correct-horse-battery"}"""
                )

                then("it reports valid=true") {
                    response.statusCode shouldBe HttpStatus.OK
                    response.body!!["valid"] shouldBe true
                }
            }

            `when`("mgmt-verify is called with the wrong password") {
                val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                val accountId = accountIdFor(email)
                stubAssertion(accountAnchor = accountId.toString())

                val response = mgmtPost(
                    "/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                    """{"password":"wrong-password"}"""
                )

                then("it reports valid=false") {
                    response.body!!["valid"] shouldBe false
                }
            }

            `when`("mgmt-set is called with a new password") {
                val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                val accountId = accountIdFor(email)
                stubAssertion(accountAnchor = accountId.toString())

                mgmtPost("/orchestrator/api/v1/tools/enroll-password/mgmt/$accountId", """{"newPassword":"brand-new-secret"}""")

                stubAssertion(accountAnchor = accountId.toString())
                val acceptsNew = mgmtPost(
                    "/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                    """{"password":"brand-new-secret"}"""
                )

                stubAssertion(accountAnchor = accountId.toString())
                val rejectsOld = mgmtPost(
                    "/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                    """{"password":"correct-horse-battery"}"""
                )

                then("a later mgmt-verify accepts the new password and rejects the old one") {
                    acceptsNew.body!!["valid"] shouldBe true
                    rejectsOld.body!!["valid"] shouldBe false
                }
            }
        }

        given("an account with no password enrolled yet") {
            fun unenrolledAccountId(): Long {
                val channelSessionId = identify()
                return jdbcTemplate.queryForObject(
                    "SELECT account_id FROM orchestrator.channel_session WHERE id = ?",
                    Long::class.java,
                    UUID.fromString(channelSessionId)
                )!!
            }

            `when`("mgmt-verify is called against it") {
                val accountId = unenrolledAccountId()
                stubAssertion(accountAnchor = accountId.toString())

                val response = mgmtPost(
                    "/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                    """{"password":"anything"}"""
                )

                then("it reports valid=false without throwing (constant-shape, no enumeration oracle)") {
                    response.body!!["valid"] shouldBe false
                }
            }

            `when`("mgmt-set tries to give it a password it never had") {
                val accountId = unenrolledAccountId()
                stubAssertion(accountAnchor = accountId.toString())

                val result = runCatching {
                    mgmtPost("/orchestrator/api/v1/tools/enroll-password/mgmt/$accountId", """{"newPassword":"brand-new-secret"}""")
                }

                then("it is refused - set only replaces, enroll-password is the way to add one") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                    jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM account.auth_method WHERE account_id = ? AND method = 'password'",
                        Int::class.java, accountId
                    ) shouldBe 0
                }
            }
        }

        given("a password replaced through Keycloak's 'reset password'") {
            `when`("the new instance is stored") {
                val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                val accountId = accountIdFor(email)
                stubAssertion(accountAnchor = accountId.toString())

                mgmtPost("/orchestrator/api/v1/tools/enroll-password/mgmt/$accountId", """{"newPassword":"brand-new-secret"}""")

                then("it carries its own 'has a password' claim, like one set up in the app") {
                    jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM account.claim c JOIN account.auth_method m ON m.id = c.auth_method_id " +
                            "WHERE m.account_id = ? AND m.method = 'password' AND m.active AND c.attribute_type = 'password_exists'",
                        Int::class.java, accountId
                    ) shouldBe 1
                }
            }
        }

        given("repeated wrong passwords through Keycloak's form") {
            `when`("the account lockout is reached") {
                val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                val accountId = accountIdFor(email)
                stubAssertion(accountAnchor = accountId.toString())

                val wrongAttempts = List(5) {
                    mgmtPost("/orchestrator/api/v1/tools/auth-password/mgmt/$accountId", """{"password":"wrong"}""")
                        .body!!["valid"]
                }
                val correctAttempt = mgmtPost("/orchestrator/api/v1/tools/auth-password/mgmt/$accountId", """{"password":"correct-horse-battery"}""")
                    .body!!["valid"]

                then("even the correct password is refused until it expires - the app's lockout, shared") {
                    wrongAttempts shouldBe List(5) { false }
                    correctAttempt shouldBe false
                }
            }
        }

        given("a mismatched peer-auth anchor") {
            `when`("mgmt-verify's channel_anchor claim doesn't match the accountId in the path") {
                val email = registerWithEmailAndPassword(password = "correct-horse-battery")
                val accountId = accountIdFor(email)
                stubAssertion(accountAnchor = "some-other-anchor")

                val result = runCatching {
                    restTemplate.exchange(
                        "http://localhost:$port/orchestrator/api/v1/tools/auth-password/mgmt/$accountId",
                        HttpMethod.POST,
                        HttpEntity(
                            """{"password":"correct-horse-battery"}""",
                            HttpHeaders().apply {
                                set("Authorization", "Bearer mock-peer-auth-token")
                                set("Content-Type", "application/json")
                            }
                        ),
                        mapType
                    )
                }

                then("it is rejected as unauthorized (same contract as a missing/invalid peer-auth assertion)") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.UNAUTHORIZED
                }
            }
        }
    }
}
