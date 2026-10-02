package com.example.identity.core.orchestrator

import io.kotest.matchers.shouldBe
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Jede Anfrage an eine bestimmte Kanal- oder Tool-Sitzung muss mit dem DPoP-Schlüssel kommen, an den der
 * Kanal gebunden ist; ein anderer bekommt 403 und ändert nichts (docs/09-dpop.md Abschnitt 3).
 */
class ChannelBindingIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private val personalData = """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15"}"""

    /** Runs [call] with another DPoP key than the one the channel is bound to. */
    private fun <T> asAnotherKey(call: () -> T): Result<T> {
        val own = currentBindingKeyRef
        currentBindingKeyRef = "another-device-key"
        try {
            return runCatching(call)
        } finally {
            currentBindingKeyRef = own
        }
    }

    private fun Result<*>.status(): HttpStatus? =
        (exceptionOrNull() as? HttpClientErrorException)?.statusCode?.let { HttpStatus.valueOf(it.value()) }

    init {
        given("a channel with a running ident-fsc tool session") {
            `when`("another DPoP key calls each of its endpoints") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc")
                    .nextRaw()["toolSessionId"] as String

                val readChannel = asAnotherKey { get("/orchestrator/api/v1/channels/$channelSessionId") }
                val startTool = asAnotherKey { post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc") }
                val readTool = asAnotherKey { get("/orchestrator/api/v1/tools/$toolSessionId/ident-fsc") }
                val patchTool = asAnotherKey { patch("/orchestrator/api/v1/tools/$toolSessionId/ident-fsc", personalData) }
                val cancel = asAnotherKey { delete("/orchestrator/api/v1/channels/$channelSessionId/journey") }
                val logout = asAnotherKey { deleteNoContent("/orchestrator/api/v1/channels/$channelSessionId") }

                val ownRead = get("/orchestrator/api/v1/tools/$toolSessionId/ident-fsc")
                val ownPatch = patch("/orchestrator/api/v1/tools/$toolSessionId/ident-fsc", personalData)

                then("reading the channel is forbidden") {
                    readChannel.status() shouldBe HttpStatus.FORBIDDEN
                }
                then("starting a tool is forbidden") {
                    startTool.status() shouldBe HttpStatus.FORBIDDEN
                }
                then("reading the tool session is forbidden") {
                    readTool.status() shouldBe HttpStatus.FORBIDDEN
                }
                then("submitting input to the tool session is forbidden") {
                    patchTool.status() shouldBe HttpStatus.FORBIDDEN
                }
                then("cancelling the journey is forbidden") {
                    cancel.status() shouldBe HttpStatus.FORBIDDEN
                }
                then("logging the channel out is forbidden") {
                    logout.status() shouldBe HttpStatus.FORBIDDEN
                }
                then("the own key still finds the tool session where it was and can go on") {
                    ownRead.nextRaw()["toolSessionId"] shouldBe toolSessionId
                    ownPatch.nextRaw()["toolSessionId"] shouldBe toolSessionId
                    ownPatch.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input")
                }
            }
        }

        given("a tool session id in the path that is no UUID or belongs to nobody") {
            `when`("the tool endpoints are called with it") {
                val malformedRead = runCatching { get("/orchestrator/api/v1/tools/not-a-uuid/ident-fsc") }
                val malformedPatch = runCatching { patch("/orchestrator/api/v1/tools/not-a-uuid/ident-fsc", personalData) }
                val unknownPatch = runCatching {
                    patch("/orchestrator/api/v1/tools/00000000-0000-0000-0000-000000000000/ident-fsc", personalData)
                }

                then("a malformed id is a bad request, on the read and the write path") {
                    malformedRead.status() shouldBe HttpStatus.BAD_REQUEST
                    malformedPatch.status() shouldBe HttpStatus.BAD_REQUEST
                }
                then("an unknown id is not found") {
                    unknownPatch.status() shouldBe HttpStatus.NOT_FOUND
                }
            }
        }
    }
}
