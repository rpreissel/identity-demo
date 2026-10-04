package com.example.identity.core.orchestrator

import com.example.identity.core.account.SignInLog
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.web.client.HttpClientErrorException

/**
 * The account-level brute-force lock, spanning fresh channels and tool sessions. Shared plumbing lives
 * in IntegrationTestSupport.
 */
class AccountRateLimitIntegrationTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var signInLog: SignInLog

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private val authPasswordOffered = mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")

    private fun freshChannel(): String = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

    /** A wrong password on a fresh auth-password tool - a failed attempt that sends nothing (the send rate limit has its own test below). */
    private fun failPassword(channelSessionId: String) {
        val toolSessionId = post("/tools/api/auth-password/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
        patch("/tools/api/auth-password/v1/$toolSessionId", """{"password":"wrong-password-123"}""")
    }

    init {
        given("an account with a password, and three password sessions opened before any failure") {
            `when`("five wrong passwords lock the account, then the right one goes to the third session") {
                registerWithEmailAndPassword(password = "correct-horse-battery")
                val (first, second, third) = List(3) { post("/tools/api/auth-password/v1?channel=${freshChannel()}").nextRaw()["toolSessionId"] as String }
                repeat(3) { runCatching { patch("/tools/api/auth-password/v1/$first", """{"password":"wrong-password-123"}""") } }
                repeat(2) { patch("/tools/api/auth-password/v1/$second", """{"password":"wrong-password-123"}""") }

                val rightPassword = runCatching { patch("/tools/api/auth-password/v1/$third", """{"password":"correct-horse-battery"}""") }

                then("the lock holds for a session opened before it, the right password included: 423") {
                    shouldThrow<HttpClientErrorException> { rightPassword.getOrThrow() }.statusCode.value() shouldBe 423
                }
            }
        }

        given("an account that requested three SMS codes within the window") {
            `when`("auth-sms is activated a fourth time, and then auth-password on the same channel") {
                seedRegisteredAccount()
                repeat(3) { post("/tools/api/auth-sms/v1?channel=${freshChannel()}") }
                val sentBefore = smsGateway.outbox().size
                val channelSessionId = freshChannel()

                val refused = runCatching { post("/tools/api/auth-sms/v1?channel=$channelSessionId") }
                val sentAfter = smsGateway.outbox().size
                val password = post("/tools/api/auth-password/v1?channel=$channelSessionId")

                then("the fourth is refused with 429 and no code is sent") {
                    shouldThrow<HttpClientErrorException> { refused.getOrThrow() }.statusCode.value() shouldBe 429
                    sentAfter shouldBe sentBefore
                }
                then("it is not a failed attempt - the login itself is not locked, a password still works") {
                    password.next() shouldBe authPasswordOffered
                }
            }
        }

        given("an account that used its SMS budget and then signed in with the code") {
            `when`("it requests codes again right after") {
                seedRegisteredAccount()
                repeat(2) { post("/tools/api/auth-sms/v1?channel=${freshChannel()}") }
                // The third send, and its TAN entered correctly.
                authenticateViaSms(freshChannel())

                val requests = List(3) { post("/tools/api/auth-sms/v1?channel=${freshChannel()}") }

                then("the budget started over: each request gets a code to enter") {
                    requests.forEach { it.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth") }
                }
            }
        }

        given("one mobile number in four spellings") {
            `when`("enroll-sms is sent each spelling in turn") {
                val channelSessionId = identify()
                confirmEmail(channelSessionId)
                val toolSessionId = post("/tools/api/enroll-sms/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
                val sentBefore = smsGateway.outbox().size
                listOf("+49 170 7654321", "+49-170-7654321", "0049/170/7654321").forEach {
                    patch("/tools/api/enroll-sms/v1/$toolSessionId", """{"phoneNumber":"$it"}""")
                }

                val fourth = runCatching {
                    patch("/tools/api/enroll-sms/v1/$toolSessionId", """{"phoneNumber":"+49 (170) 765 43-21"}""")
                }

                then("the send throttle counts them as one - three codes go out, the fourth is refused with 429") {
                    shouldThrow<HttpClientErrorException> { fourth.getOrThrow() }.statusCode.value() shouldBe 429
                    smsGateway.outbox().size - sentBefore shouldBe 3
                }
            }
        }

        given("someone guessing an account's password, each guess on a fresh channel") {
            `when`("five guesses fail and auth-password is asked for once more") {
                val accountId = seedRegisteredAccount()
                // Each iteration is a fresh channel with a fresh journey attempt budget, which the
                // journey-local budget cannot catch and the account-level rate limit must
                // (docs/04-orchestrierung.md #7).
                repeat(5) { failPassword(freshChannel()) }

                val locked = runCatching { post("/tools/api/auth-password/v1?channel=${freshChannel()}") }

                then("the account-level throttle locks") {
                    shouldThrow<HttpClientErrorException> { locked.getOrThrow() }.statusCode.value() shouldBe 423
                }
                then("every wrong guess is in the sign-in log, and the one that locked the account as the lockout") {
                    signInLog.of(accountId).map { it.signInType } shouldBe List(5) { "SIGN_IN_FAILED" } + "LOCKED_OUT"
                    signInLog.of(accountId).first().details["method"] shouldBe "password"
                }
            }
        }

        given("an account with a few failed password guesses") {
            `when`("a successful auth follows, then two more failures") {
                seedRegisteredAccount()
                // Each failure gets its own channel, or the journey-local attempt budget would end the journey first.
                repeat(3) { failPassword(freshChannel()) }
                val authenticated = authenticateViaSms(freshChannel())
                repeat(2) { failPassword(freshChannel()) }

                val stillAllowed = post("/tools/api/auth-password/v1?channel=${freshChannel()}")

                then("the success authenticates") {
                    authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                }
                then("the counter was reset, not just not yet locked: 2 failures since then do not count as 5") {
                    stillAllowed.next() shouldBe authPasswordOffered
                }
            }
        }
    }
}
