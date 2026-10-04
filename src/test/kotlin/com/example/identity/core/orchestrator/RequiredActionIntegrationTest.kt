package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.support.AccountFixtures
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * Registration's Required Actions (docs/04-orchestrierung.md #2, Keycloak's "Required Action"
 * concept) where the channel cannot discharge them: an account without a confirmed email still logs
 * in and manages its methods. The full registration with the address is in
 * RegistrationFlowIntegrationTest.
 */
class RequiredActionIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    /** An account with sms only and no confirmed email, bound to this device. */
    private fun seedAccountWithoutEmail() {
        accountFixtures.seedAccount(
            email = null,
            methods = listOf(AccountFixtures.Method.Sms()),
            bindDeviceKeyRef = currentBindingKeyRef
        )
    }

    /** Opens a fresh channel on this device and logs in via sms; returns the channelSessionId. */
    private fun loginViaSms(): String {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        authenticateViaSms(channelSessionId)
        return channelSessionId
    }

    init {
        given("a channel that does not offer confirm-email") {
            `when`("the person identifies and enrolls sms") {
                val channelSessionId = post(
                    "/orchestrator/api/v1/app/channels",
                    """{"availableTools":["ident-fsc@1","enroll-sms@1"]}"""
                ).channel()["channelSessionId"] as String
                reIdentifyViaFsc(channelSessionId)
                enrollSms(channelSessionId)
                val channel = get("/orchestrator/api/v1/channels/$channelSessionId").channel()

                then("the address step is skipped, not blocked, and the run finishes on sms alone") {
                    channel["state"] shouldBe "AUTHENTICATED"
                }
            }
        }

        given("an account without a confirmed email, bound to this device") {
            `when`("a fresh channel logs in via sms") {
                seedAccountWithoutEmail()
                val newChannel = post("/orchestrator/api/v1/app/channels")
                val authenticated = authenticateViaSms(newChannel.channel()["channelSessionId"] as String)

                then("the channel offers sms login, without an email demand") {
                    newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
                }
                then("the login succeeds") {
                    authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                }
            }

            `when`("the session logged in via sms requests an enrollment") {
                seedAccountWithoutEmail()
                val channelSessionId = loginViaSms()

                val started = triggerEnrollmentStepUp(channelSessionId)

                then("a step-up is proposed first - MANAGE demands loa2, sms proved only loa1") {
                    // selfServiceAcrFloor for an identified account. Candidate computation is
                    // unit-tested (ReIdentifyStrategyTest); here only the round trip.
                    started.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                }
            }

            `when`("the session accepts the step-up and re-identifies via ident-fsc") {
                seedAccountWithoutEmail()
                val channelSessionId = loginViaSms()
                triggerEnrollmentStepUp(channelSessionId)

                val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                val reIdentified = reIdentifyViaFsc(channelSessionId)

                then("accepting offers the identification methods") {
                    accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                }
                then("the parked MANAGE wish resumes with a selection page") {
                    reIdentified.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                }
                then("enroll-password stays excluded - the skipped address step does not waive its email precondition") {
                    @Suppress("UNCHECKED_CAST")
                    val options = reIdentified.stepData()["options"] as List<String>
                    // Not exact, so new enrollment methods in the catalog do not force an edit here.
                    options shouldContainAll listOf("enroll-device", "enroll-qr")
                    options shouldNotContain "enroll-password"
                }
            }
        }
    }
}
