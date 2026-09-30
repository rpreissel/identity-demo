package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * The account-level brute-force lock, spanning fresh channels and tool sessions. Shared plumbing lives
 * in IntegrationTestSupport.
 */
class AccountRateLimitIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    /** A wrong password on a fresh auth-password tool - a failed attempt that sends nothing (the send rate limit has its own test below). */
    private fun failPassword(channelSessionId: String) {
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
        patch("/orchestrator/api/v1/tools/$toolSessionId/auth-password", """{"password":"wrong-password-123"}""")
    }

    init {
        given("an account that keeps requesting SMS codes") {
            `when`("auth-sms is activated a fourth time within the window") {
                then("it is refused with 429 and no code is sent - not a failed attempt") {
                    seedRegisteredAccount()
                    repeat(3) {
                        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                        post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                    }
                    val sentBefore = smsGateway.outbox().size
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    val refused = assertThrows<HttpClientErrorException> {
                        post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                    }
                    refused.statusCode.value() shouldBe 429
                    smsGateway.outbox().size shouldBe sentBefore
                    // The login itself is not locked - a password still works.
                    post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password").nextRaw()["toolSessionId"].shouldNotBeNull()
                }
            }
        }

        given("an account that used its SMS budget and then signed in with the code") {
            `when`("it requests codes again right after") {
                then("the budget started over: the one who asked received the codes") {
                    seedRegisteredAccount()
                    repeat(2) {
                        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                        post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                    }
                    // The third send, and its TAN entered correctly.
                    authenticateViaSms(post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String)

                    repeat(3) {
                        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                        post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"].shouldNotBeNull()
                    }
                }
            }
        }

        given("one mobile number in four spellings") {
            then("the send throttle counts them as one - three codes go out, the fourth is refused with 429") {
                val channelSessionId = identify()
                confirmEmail(channelSessionId)
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val sentBefore = smsGateway.outbox().size
                listOf("+49 170 7654321", "+49-170-7654321", "0049/170/7654321").forEach {
                    patch("/orchestrator/api/v1/tools/$toolSessionId/enroll-sms", """{"phoneNumber":"$it"}""")
                }
                val refused = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$toolSessionId/enroll-sms", """{"phoneNumber":"+49 (170) 765 43-21"}""")
                }
                refused.statusCode.value() shouldBe 429
                smsGateway.outbox().size - sentBefore shouldBe 3
            }
        }

        given("a fresh channel") {
            `when`("repeatedly failing auth across fresh tool sessions") {
                then("the account-level throttle locks") {

                seedRegisteredAccount()
                // Each iteration is a fresh channel with a fresh journey attempt budget, which the
                // journey-local budget cannot catch and the account-level rate limit must
                // (docs/04-orchestrierung.md #7).
                repeat(5) {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    failPassword(channelSessionId)
                }

                val lockedChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val exception = assertThrows<HttpClientErrorException> {
                    post("/orchestrator/api/v1/channels/$lockedChannelSessionId/tools/auth-password")
                }
                exception.statusCode.value() shouldBe 423


                }
            }
        }

        given("a fresh channel") {
            `when`("a successful auth follows a few failures") {
                then("the throttle counter resets") {

                seedRegisteredAccount()
                // A few failures below the lock, then a success clears the counter. Each failure gets its
                // own channel, or the journey-local attempt budget would end the journey first.
                repeat(3) {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    failPassword(channelSessionId)
                }
                val freshChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val authenticated = authenticateViaSms(freshChannelSessionId)
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                // The counter was reset, not just "not yet locked": two more failures on another new
                // session (same account via the device link) must not count as 3/5.
                repeat(2) {
                    val retryChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                    failPassword(retryChannelSessionId)
                }
                // Still allowed - only 2 failures since the reset, well under the lock threshold.
                val nextChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val stillAllowed = post("/orchestrator/api/v1/channels/$nextChannelSessionId/tools/auth-password")
                stillAllowed.nextRaw()["toolSessionId"].shouldNotBeNull()


                }
            }
        }
    }
}
