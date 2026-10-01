package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Backing out of the currently activated tool (Back/Switch). Shared plumbing lives in
 * IntegrationTestSupport.
 */
class SwitchBackIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a fresh channel") {
            `when`("switching away from the ident-fsc tool") {
                then("the fallback chain moves on to the remaining identification candidates") {

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

                // Identification is a fallback state (declining moves on, as in PreferredAuth/AuthChoice):
                // ident-fsc is declined and not offered again, leaving the other two identification tools.
                val result = delete("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc")
                result.stepData()["options"] shouldBe listOf("ident-eid", "ident-nect")

                val channel = get("/orchestrator/api/v1/channels/$channelSessionId")
                // Still nothing identified, so no account is being set up yet (ADR-46).
                channel.channel()["state"] shouldBe "ANONYMOUS"


                }
            }
        }

        given("a fresh channel, going back instead of declining") {
            `when`("going back from the ident-fsc tool") {
                then("the identification choice comes back with ident-fsc still on it, and the tool session is gone") {

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

                // "Zurück" is not "Anderes Verfahren": nothing is declined, so the choice the user came
                // from is shown again.
                val result = post("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc/back")
                result.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                @Suppress("UNCHECKED_CAST")
                (result.stepData()["options"] as List<String>) shouldContainAll listOf("ident-fsc", "ident-eid", "ident-nect")

                assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")
                }.statusCode shouldBe HttpStatus.NOT_FOUND

                // And it can be chosen again right away.
                post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] shouldNotBe identToolSessionId
                }
            }
        }

        given("an identified channel") {
            `when`("switching away from an enroll tool") {
                then("enrollment candidates are re-offered") {

                val channelSessionId = identifyAndConfirmEmail()
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String

                // Four enrollment methods are offerable (enroll-password too, the address is confirmed),
                // so switching away re-offers the selection page; the old tool session is abandoned.
                val result = delete("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms")
                result.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val resultOptions = result.stepData()["options"] as List<String>
                // shouldContainAll (not exact): new enrollment methods elsewhere in the catalog
                // don't change this.
                resultOptions shouldContainAll listOf("enroll-sms", "enroll-device", "enroll-qr", "enroll-password")

                // The demo column keeps saying whose session this is: leaving a tool answers with
                // the demo block like every other tool response.
                @Suppress("UNCHECKED_CAST")
                val session = (result["demo"] as Map<String, Any?>)["session"] as Map<String, Any?>
                session["amr"] shouldBe listOf("fsc")

                // The abandoned tool session is gone even though we re-activate the same toolId.
                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                exception.statusCode shouldBe HttpStatus.NOT_FOUND

                // Re-activating works fine and mints a new tool session.
                val reactivated = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms")
                reactivated.nextRaw()["toolSessionId"] shouldNotBe enrollToolSessionId


                }
            }
        }
    }
}
