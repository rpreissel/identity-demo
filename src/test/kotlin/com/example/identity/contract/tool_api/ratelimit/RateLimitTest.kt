package com.example.identity.contract.tool_api.ratelimit

import com.example.identity.tools.auth_email.internal.EmailSendLimit
import com.example.identity.tools.auth_sms.internal.SmsSendLimit
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class RateLimitTest : BehaviorSpec({

    given("the send budgets of auth_sms and auth_email") {
        `when`("their namespaces are derived") {
            val sms = RateLimit.namespaceOf(SmsSendLimit::class.java)
            val email = RateLimit.namespaceOf(EmailSendLimit::class.java)

            then("each names the module and the class") {
                sms shouldBe "auth_sms.SmsSendLimit"
                email shouldBe "auth_email.EmailSendLimit"
            }
        }
    }
})
