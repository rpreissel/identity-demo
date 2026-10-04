package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Backing out of the currently activated tool (Back/Switch). Shared plumbing lives in
 * IntegrationTestSupport. Which candidates a declined tool leaves is the strategies' part
 * (RegisterStrategyTest).
 */
class SwitchBackIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    init {
        given("a fresh channel with ident-fsc active") {
            `when`("going back from the ident-fsc tool, then using the old tool session and choosing ident-fsc again") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/tools/api/ident-fsc/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String

                val result = post("/tools/api/ident-fsc/v1/$identToolSessionId/back")
                val oldSession = runCatching { patch("/tools/api/ident-fsc/v1/$identToolSessionId", """{"fsc":"VALIDCODE"}""") }
                val chosenAgain = post("/tools/api/ident-fsc/v1?channel=$channelSessionId")

                then("the identification choice comes back with ident-fsc still on it") {
                    // "Zurück" is not "Anderes Verfahren": nothing is declined, so the choice the user
                    // came from is shown again.
                    result.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                    @Suppress("UNCHECKED_CAST")
                    (result.stepData()["options"] as List<String>) shouldContainAll listOf("ident-fsc", "ident-eid", "ident-nect")
                }
                then("the tool session is gone") {
                    shouldThrow<HttpClientErrorException> { oldSession.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
                then("ident-fsc can be chosen again right away, in a new tool session") {
                    chosenAgain.nextRaw()["toolSessionId"] shouldNotBe identToolSessionId
                }
            }
        }

        given("an identified channel with enroll-sms active") {
            `when`("switching away from the enroll tool, then using the old tool session and re-activating enroll-sms") {
                val channelSessionId = identifyAndConfirmEmail()
                val enrollToolSessionId = post("/tools/api/enroll-sms/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String

                val result = delete("/tools/api/enroll-sms/v1/$enrollToolSessionId")
                val oldSession = runCatching {
                    patch("/tools/api/enroll-sms/v1/$enrollToolSessionId", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val reactivated = post("/tools/api/enroll-sms/v1?channel=$channelSessionId")

                then("the enrollment candidates are re-offered on the selection page") {
                    // Four enrollment methods are offerable (enroll-password too, the address is confirmed).
                    result.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                    // shouldContainAll (not exact): new enrollment methods elsewhere in the catalog
                    // don't change this.
                    @Suppress("UNCHECKED_CAST")
                    (result.stepData()["options"] as List<String>) shouldContainAll listOf("enroll-sms", "enroll-device", "enroll-qr", "enroll-password")
                }
                then("leaving a tool answers with the demo block, which keeps saying whose session this is") {
                    @Suppress("UNCHECKED_CAST")
                    val session = (result["demo"] as Map<String, Any?>)["session"] as Map<String, Any?>
                    session["amr"] shouldBe listOf("fsc")
                }
                then("the abandoned tool session is gone") {
                    shouldThrow<HttpClientErrorException> { oldSession.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
                then("re-activating the same tool mints a new tool session") {
                    reactivated.nextRaw()["toolSessionId"] shouldNotBe enrollToolSessionId
                }
            }
        }
    }
}
