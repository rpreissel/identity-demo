package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Registration's Required Actions (docs/04-orchestrierung.md #2, Keycloak's "Required Action"
 * concept): REGISTRATION finishes only with a confirmed email, even when the channel's requiredAcr
 * is already reached by other means.
 */
class RequiredActionIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("registration's required actions") {
        then("Registration demands the confirmed address before a single method is offered") {
            val channelSessionId = identify()

            // The address comes first because enroll-password depends on it.
            val identified = get("/orchestrator/api/v1/channels/$channelSessionId")
            identified.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-email", "step" to "input")

            confirmEmail(channelSessionId)

            // sms alone reaches loa1, but the factor-kind obligation is still open.
            enrollSms(channelSessionId)
            val channelMidway = get("/orchestrator/api/v1/channels/$channelSessionId")
            // sms makes the account set up (ADR-46); the open obligation belongs to the journey.
            channelMidway.channel()["state"] shouldBe "ANONYMOUS"

            enrollPassword(channelSessionId)

            val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
            finalChannel.channel()["state"] shouldBe "AUTHENTICATED"
            @Suppress("UNCHECKED_CAST")
            (finalChannel.channel()["activeMethods"] as List<*>).methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")
        }
        then("Registration discharges both required actions - the address, then the password") {
            val channelSessionId = identify()

            // The attested address unlocks enroll-password (ClaimRequirement(EMAIL, PROVEN)).
            val email = confirmEmail(channelSessionId)
            enrollSms(channelSessionId)
            enrollPassword(channelSessionId)
            val enrolled = get("/orchestrator/api/v1/channels/$channelSessionId")
            enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

            val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")
            @Suppress("UNCHECKED_CAST")
            (finalChannel.channel()["activeMethods"] as List<*>).methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")
        }
        then("Existing account without confirmed email can still login and add a method via manage methods") {
            // The channel does not support confirm-email, so the address step is skipped, not
            // blocked, and the run finishes on sms alone. This yields an account without email.
            val channelSessionId = post(
                "/orchestrator/api/v1/app/channels",
                """{"availableTools":["ident-fsc","enroll-sms"]}"""
            ).channel()["channelSessionId"] as String
            reIdentifyViaFsc(channelSessionId)
            val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
            val (tan, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
            get("/orchestrator/api/v1/channels/$channelSessionId").channel()["state"] shouldBe "AUTHENTICATED"

            // A fresh channel on the same device logs in via sms, without an email demand.
            val newChannel = post("/orchestrator/api/v1/app/channels")
            newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
            val newChannelSessionId = newChannel.channel()["channelSessionId"] as String

            val authenticated = authenticateViaSms(newChannelSessionId)
            authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

            // MANAGE demands loa2 for an identified account (selfServiceAcrFloor). This session
            // proved only sms (loa1), so a step-up via re-identification comes first.
            val started = triggerEnrollmentStepUp(newChannelSessionId)
            started.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
            // Candidate computation is unit-tested (ReIdentifyStrategyTest); here only the round trip.
            val accepted = post("/orchestrator/api/v1/channels/$newChannelSessionId/answer", """{"answer":"accept"}""")
            accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
            val reIdentified = reIdentifyViaFsc(newChannelSessionId)
            // The parked MANAGE wish resumes with a selection page. enroll-password stays excluded:
            // the skipped address step does not waive its email precondition.
            reIdentified.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
            @Suppress("UNCHECKED_CAST")
            val reIdentifiedOptions = reIdentified.stepData()["options"] as List<String>
            // Not exact, so new enrollment methods in the catalog do not force an edit here.
            reIdentifiedOptions shouldContainAll listOf("enroll-device", "enroll-qr")
            reIdentifiedOptions shouldNotContain "enroll-password"
        }
        }
    }
}
