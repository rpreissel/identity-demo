package com.example.identity.core.orchestrator.session

import com.example.identity.tools.auth_email.internal.EmailSendLimit
import com.example.identity.tools.auth_sms.internal.SmsSendLimit
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.CapturingSlot
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.Duration
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Unit test of [ModuleRateLimits] with the counter mocked: the key a module counts reaches the
 * counter, and so the `subject` column, only as HMAC-SHA256 under the OTP pepper
 * (docs/03-tool-architektur.md, ADR-44).
 */
class ModuleRateLimitsTest : BehaviorSpec({

    val pepper = "test-otp-pepper"

    fun hmac(key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper.toByteArray(), "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(key.toByteArray()))
    }

    fun counterCapturing(subject: CapturingSlot<String>): RateLimitCounter = mockk {
        every { recordWindowedAttempt(any<String>(), capture(subject), any(), any<Duration>()) } returns true
        every { reset(any<String>(), capture(subject)) } returns Unit
    }

    given("the SMS send budget of a mobile number") {
        val subject = slot<String>()
        val counter = counterCapturing(subject)
        val limit = SmsSendLimit(ModuleRateLimits(counter, pepper))

        `when`("a TAN to that number is counted") {
            limit.trySend("+49-170 1234567")

            then("the counter gets the HMAC of the normalized number, not the number") {
                subject.captured shouldBe hmac("+491701234567")
                subject.captured shouldNotContain "1701234567"
                verify(exactly = 1) {
                    counter.recordWindowedAttempt("auth_sms.SmsSendLimit", hmac("+491701234567"), 3, Duration.ofMinutes(10))
                }
            }
        }

        `when`("the budget of that number is reset") {
            limit.received("0049 170 1234567")

            then("the reset names the same HMAC") {
                subject.captured shouldBe hmac("+491701234567")
                verify(exactly = 1) { counter.reset("auth_sms.SmsSendLimit", hmac("+491701234567")) }
            }
        }
    }

    given("the e-mail send budget of an address") {
        val subject = slot<String>()
        val counter = counterCapturing(subject)
        val limit = EmailSendLimit(ModuleRateLimits(counter, pepper))

        `when`("a code to that address is counted") {
            limit.trySend(" Anna.Muster@Example.org ")

            then("the counter gets the HMAC of the normalized address, not the address") {
                subject.captured shouldBe hmac("anna.muster@example.org")
                subject.captured.lowercase() shouldNotContain "anna"
                subject.captured.lowercase() shouldNotContain "example.org"
            }
        }
    }
})
