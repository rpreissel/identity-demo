package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.SignInLog
import com.example.identity.core.orchestrator.keycloak.PeerAuthAssertion
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.kotest.assertions.throwables.shouldThrow
import org.springframework.web.client.HttpClientErrorException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import java.time.Instant
import java.util.UUID

/**
 * The account's sign-in log (ADR-39, addendum): what a sign-in, a failed proof and a sign-out leave
 * behind - from the app channel, and from Keycloak, whose own logouts the orchestrator only learns
 * about because Keycloak reports them. The lockout's entries are checked
 * with the lock itself in AccountRateLimitIntegrationTest.
 */
class SignInLogIntegrationTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var signInLog: SignInLog

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun accountOf(channelSessionId: String): AccountId =
        AccountId(jdbcTemplate.queryForObject(
            "SELECT account_id FROM orchestrator.channel_session WHERE id = CAST(? AS UUID)", Long::class.java, channelSessionId
        )!!)

    private fun typesOf(accountId: AccountId) = signInLog.of(accountId).map { it.signInType }

    private fun stubAssertion(binding: String) {
        every { peerAuthValidator.validate(any(), any(), any(), any()) } returns
            PeerAuthAssertion(jti = UUID.randomUUID().toString(), issuedAt = Instant.now(), channelBinding = binding, subject = null)
    }

    private fun keycloakPost(path: String, body: String? = null) =
        restTemplate.exchange(
            "http://localhost:$port$path", HttpMethod.POST,
            HttpEntity(body, HttpHeaders().apply {
                set("Authorization", "Bearer mock-peer-auth-token")
                set("Content-Type", "application/json")
            }),
            String::class.java
        )

    init {
        given("a returning user on the app") {
            `when`("the user signs in") {
                val channelSessionId = loginAsSeededAccount()
                val accountId = accountOf(channelSessionId)

                then("the sign-in is logged with its level, proofs and the tools in the versions the app spoke") {
                    val signedIn = signInLog.of(accountId).single()
                    signedIn.signInType shouldBe "SIGNED_IN"
                    signedIn.channel shouldBe "APP"
                    signedIn.acr shouldBe "loa2"
                    @Suppress("UNCHECKED_CAST")
                    (signedIn.details["amr"] as List<String>) shouldContainAll listOf("sms", "password")
                    @Suppress("UNCHECKED_CAST")
                    (signedIn.details["tools"] as List<String>) shouldContainAll listOf("auth-sms@1", "auth-password@1")
                    signedIn.details["type"] shouldBe "SIGNED_IN"
                    signedIn.details["version"] shouldBe 2
                }
            }

            `when`("the user signs out again") {
                val channelSessionId = loginAsSeededAccount()
                val accountId = accountOf(channelSessionId)
                deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId")

                then("the sign-out is logged with who ended it") {
                    val signedOut = signInLog.of(accountId).last()
                    signedOut.signInType shouldBe "SIGNED_OUT"
                    signedOut.details["endedBy"] shouldBe "HOLDER"
                }
            }
        }

        given("a user signed in at loa1") {
            `when`("the user raises the level to loa2") {
                seedRegisteredAccount()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                authenticateViaSms(channelSessionId)
                post("/orchestrator/api/v1/channels/$channelSessionId/step-ups", """{"requiredAcr":"loa2"}""")
                authenticateViaPassword(channelSessionId)
                val accountId = accountOf(channelSessionId)

                then("the step-up is its own entry after the sign-in, with the level it reached") {
                    typesOf(accountId) shouldBe listOf("SIGNED_IN", "STEPPED_UP")
                    val steppedUp = signInLog.of(accountId).last()
                    steppedUp.acr shouldBe "loa2"
                    steppedUp.channel shouldBe "APP"
                    @Suppress("UNCHECKED_CAST")
                    (steppedUp.details["amr"] as List<String>) shouldContainAll listOf("sms", "password")
                    @Suppress("UNCHECKED_CAST")
                    (steppedUp.details["tools"] as List<String>) shouldContainAll listOf("auth-sms@1", "auth-password@1")
                }
            }
        }

        given("a logout that only Keycloak saw") {
            `when`("Keycloak reports it for the account its assertion is bound to") {
                val accountId = seedRegisteredAccount()
                stubAssertion(accountId.toString())
                val response = keycloakPost("/orchestrator/api/v1/kc/accounts/$accountId/sign-outs?kcSessionId=kc-session-1")

                then("the report is accepted") {
                    response.statusCode shouldBe HttpStatus.NO_CONTENT
                }
                then("it is logged as a sign-out on the Web channel") {
                    val signedOut = signInLog.of(accountId).single()
                    signedOut.signInType shouldBe "SIGNED_OUT"
                    signedOut.channel shouldBe "WEB"
                }
            }

            `when`("Keycloak reports it with an assertion bound to another account") {
                val accountId = seedRegisteredAccount()
                stubAssertion("someone-else")
                val result = runCatching {
                    keycloakPost("/orchestrator/api/v1/kc/accounts/$accountId/sign-outs?kcSessionId=kc-session-1")
                }

                then("the report is refused") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }
                }
                then("nothing is logged") {
                    signInLog.of(accountId) shouldBe emptyList()
                }
            }
        }
    }
}
