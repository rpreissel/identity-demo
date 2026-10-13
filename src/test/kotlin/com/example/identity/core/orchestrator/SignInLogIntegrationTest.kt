package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.SignInLog
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.example.identity.core.orchestrator.session.KeycloakSessionEvidenceInitializer
import com.example.identity.core.orchestrator.session.MethodEvidenceRow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.time.Instant

/**
 * The account's sign-in log (ADR-39, addendum): what a sign-in, a failed proof and a sign-out leave
 * behind - from the app channel, and from Keycloak, whose own logouts the orchestrator learns about
 * over the back channel. The lockout's entries are checked
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

    @Autowired
    private lateinit var keycloakSessionEvidence: KeycloakSessionEvidenceInitializer

    /** A Web sign-in into Keycloak session [kcSessionId], as far as the orchestrator records it (ADR-59). */
    private fun holdKeycloakSession(kcSessionId: String, accountId: AccountId) {
        val method = MethodEvidence(MethodName("password"), AcrLevel.LOA1, amrSourceId = "auth-password", provenAt = Instant.now())
        keycloakSessionEvidence.createIfAbsent(kcSessionId, accountId.value, MethodEvidenceRow.of(method), Instant.now().plusSeconds(1800))
    }

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

        given("a logout that only Keycloak saw, of a session the orchestrator holds evidence for") {
            `when`("Keycloak reports it over the back channel") {
                val accountId = seedRegisteredAccount()
                holdKeycloakSession("kc-session-1", accountId)
                val response = backchannelLogout("kc-session-1")

                then("the report is accepted") {
                    response.statusCode shouldBe HttpStatus.OK
                }
                then("it is logged once, as a sign-out on the Web channel") {
                    val signedOut = signInLog.of(accountId).single()
                    signedOut.signInType shouldBe "SIGNED_OUT"
                    signedOut.channel shouldBe "WEB"
                }
            }

            `when`("the same logout_token arrives a second time") {
                val accountId = seedRegisteredAccount()
                holdKeycloakSession("kc-session-1", accountId)
                val token = LogoutTokens.signed("kc-session-1")
                backchannelLogout("kc-session-1", token)
                val replay = backchannelLogout("kc-session-1", token)

                then("the replay is refused and nothing more is logged") {
                    replay.statusCode shouldBe HttpStatus.BAD_REQUEST
                    signInLog.of(accountId).size shouldBe 1
                }
            }

            `when`("a logout_token signed with a key the realm never published arrives") {
                val accountId = seedRegisteredAccount()
                holdKeycloakSession("kc-session-1", accountId)
                val response = backchannelLogout("kc-session-1", LogoutTokens.signed("kc-session-1", signingKey = LogoutTokens.foreignKey()))

                then("it is refused, without saying why, and nothing is logged") {
                    response.statusCode shouldBe HttpStatus.BAD_REQUEST
                    response.body shouldBe """{"error":"invalid_request"}"""
                    signInLog.of(accountId) shouldBe emptyList()
                }
            }
        }

        given("a valid logout_token sent in the address instead of the body") {
            `when`("it arrives") {
                val accountId = seedRegisteredAccount()
                holdKeycloakSession("kc-session-1", accountId)
                val response = backchannelLogout("kc-session-1", inQuery = true)

                then("it is refused and nothing is logged") {
                    response.statusCode shouldBe HttpStatus.BAD_REQUEST
                    signInLog.of(accountId) shouldBe emptyList()
                }
            }
        }

        given("a session the orchestrator knows nothing of") {
            `when`("Keycloak reports its logout") {
                val accountId = seedRegisteredAccount()
                val response = backchannelLogout("kc-session-unknown")

                then("it is accepted and nothing is logged") {
                    response.statusCode shouldBe HttpStatus.OK
                    signInLog.of(accountId) shouldBe emptyList()
                }
            }
        }

        given("a user who signed out on the app") {
            `when`("Keycloak then reports the end of that login's session") {
                val channelSessionId = loginAsSeededAccount()
                val accountId = accountOf(channelSessionId)
                val kcSessionId = jdbcTemplate.queryForObject(
                    "SELECT a.keycloak_session_id FROM orchestrator.channel_session c JOIN orchestrator.app_token_session a " +
                        "ON a.id = c.app_token_session_id WHERE c.id = CAST(? AS UUID)", String::class.java, channelSessionId
                )!!
                deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId")
                val response = backchannelLogout(kcSessionId)

                then("the confirmation adds no second sign-out") {
                    response.statusCode shouldBe HttpStatus.OK
                    typesOf(accountId) shouldBe listOf("SIGNED_IN", "SIGNED_OUT")
                }
            }
        }
    }
}
