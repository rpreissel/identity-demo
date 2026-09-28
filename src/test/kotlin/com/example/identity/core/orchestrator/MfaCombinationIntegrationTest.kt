package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Combining two factor types to reach a higher ACR than either alone.
 * Shared plumbing lives in IntegrationTestSupport.
 */
class MfaCombinationIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a registered and authenticated account (fsc + sms + confirmed email)") {
            `when`("requesting a step-up to a level no active method reaches") {
                then("re-identification is offered first; declining it falls back to the already-authenticated channel") {

                // Reuse the happy path up to AUTHENTICATED at loa2.
                val channelSessionId = loginAsSeededAccount()

                // The enrolled methods reach loa2 but not loa3. ident-eid (maxAcr=loa3) can, so it is
                // offered.
                val offered = post("/orchestrator/api/v1/channels/$channelSessionId/step-ups", """{"requiredAcr":"loa3"}""")
                offered.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                // Declining RE_IDENTIFY cancels only the sub-journey. The suspended STEP_UP resumes and
                // cancels itself instead of re-offering the same prompt forever, so the channel falls
                // back to AUTHENTICATED and keeps the valid session.
                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                declined.channel()["state"] shouldBe "AUTHENTICATED"


                }
            }
        }

        given("a fresh channel") {
            `when`("combining sms and email, each only loa1 alone") {
                then("together they reach loa2") {

                // The channel requires loa2, so registration needs a second factor type.
                val channelSessionId = identifyAndConfirmEmail(requiredAcr = "loa2")
                // First factor: sms, alone only loa1.
                val enrollSmsToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (smsTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val afterSms = patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
                // The address was confirmed before any enrollment (ADR-17), so enroll-password is
                // offered as the knowledge factor for loa2.
                @Suppress("UNCHECKED_CAST")
                (afterSms.stepData()["options"] as List<String>) shouldContain "enroll-password"
                // Second factor: password (KNOWLEDGE) plus sms (POSSESSION) reach loa2.
                enrollPassword(channelSessionId)

                val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
                finalChannel.channel()["currentAcr"] shouldBe "loa2"
                @Suppress("UNCHECKED_CAST")
                finalChannel.channel()["currentAmr"] as List<String> shouldContainExactlyInAnyOrder listOf("fsc", "sms", "password")

                // --- Fresh app session on the same device, without re-identification (no fsc loa2) ---
                val loginStart = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                // sms and password are each loa1, but POSSESSION and KNOWLEDGE combine to loa2.
                loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                loginStart.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")

                val afterSmsAuth = authenticateViaSms(newChannelSessionId)
                // After sms (loa1) the password is offered as the second factor type.
                afterSmsAuth.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")

                val emailActivation = post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-password")
                val authEmailToolSessionId = emailActivation.nextRaw()["toolSessionId"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$authEmailToolSessionId/auth-password", """{"password":"correct-horse-battery"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterLogin.channel()["currentAcr"] shouldBe "loa2"


                }
            }
        }
    }
}
