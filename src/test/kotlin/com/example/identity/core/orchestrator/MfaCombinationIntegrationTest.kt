package com.example.identity.core.orchestrator

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Combining two factor types to reach a higher ACR than either alone.
 * Shared plumbing lives in IntegrationTestSupport.
 */
class MfaCombinationIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    init {
        given("a channel logged in at loa2 with sms and password") {
            `when`("a step-up to a level no active method reaches is requested and the re-identification declined") {
                val channelSessionId = loginAsSeededAccount()

                val offered = post("/orchestrator/api/v1/channels/$channelSessionId/step-ups", """{"requiredAcr":"loa3"}""")
                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")

                then("re-identification is offered first") {
                    // The enrolled methods reach loa2 but not loa3. ident-eid (maxAcr=loa3) can.
                    offered.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                }
                then("declining falls back to the already-authenticated channel") {
                    // Declining RE_IDENTIFY cancels only the sub-journey. The suspended STEP_UP resumes and
                    // cancels itself instead of re-offering the same prompt forever.
                    declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    declined.channel()["state"] shouldBe "AUTHENTICATED"
                }
            }
        }

        given("an account with sms and password, each only loa1 alone, bound to this device") {
            `when`("a fresh loa2 channel logs in with sms and then the password") {
                seedRegisteredAccount()
                val loginStart = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val channelSessionId = loginStart.channel()["channelSessionId"] as String

                val afterSms = authenticateViaSms(channelSessionId)
                val afterPassword = authenticateViaPassword(channelSessionId)
                val channel = get("/orchestrator/api/v1/channels/$channelSessionId").channel()

                then("both methods are offered") {
                    loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    @Suppress("UNCHECKED_CAST")
                    (loginStart.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
                then("after sms the password is offered as the second factor type") {
                    afterSms.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")
                }
                then("possession and knowledge together reach loa2") {
                    afterPassword.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    channel["currentAcr"] shouldBe "loa2"
                }
            }
        }
    }
}
