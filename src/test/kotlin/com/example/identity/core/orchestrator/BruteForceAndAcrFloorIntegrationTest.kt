package com.example.identity.core.orchestrator

import com.example.identity.contract.texts.templateOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * Guards the lookup-login path, which any device reaches without pairing: the channel's acrFloor
 * applies, and failed attempts count per account although `channel.accountId` is still null.
 * A regression here breaks no flow; it only shows as a limit that never trips.
 */
class BruteForceAndAcrFloorIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    /** `ChannelCreationRateLimitService`'s budget of channels per binding key and window. */
    private val channelsPerWindow = 20

    init {
        given("an account with e-mail and password, reached by lookup login on a channel that demands loa2") {
            `when`("the password alone is proven") {
                val password = "correct-horse-battery"
                val email = registerWithEmailAndPassword(password)
                val channelSessionId = post(
                    "/orchestrator/api/v1/app/channels",
                    """{"intent":"lookup_login","requiredAcr":"loa2"}"""
                ).channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup")
                    .nextRaw()["toolSessionId"] as String

                val afterPassword = patch(
                    "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                    """{"email":"$email","password":"$password"}"""
                )

                then("the journey asks for the account's second factor instead of settling into AUTHENTICATED") {
                    afterPassword.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
                    afterPassword.channel()["state"] shouldNotBe "AUTHENTICATED"
                }
            }
        }

        given("an account whose password is being guessed from fresh channels") {
            `when`("five wrong passwords are submitted, each in its own journey, then the right one") {
                val password = "correct-horse-battery"
                val email = registerWithEmailAndPassword(password)
                // One guess per channel: a fresh journey resets AuthJourney.attemptBudget, so only
                // an account-level counter can stop this.
                repeat(5) { attempt -> submitLookupPassword(email, "wrong-guess-$attempt") }

                val withRightPassword = submitLookupPassword(email, password)

                then("the account is locked, and the CORRECT password stops working too") {
                    withRightPassword.channel()["state"] shouldNotBe "AUTHENTICATED"
                }
                then("the lock is answered as an ordinary failed attempt - a distinct one would tell which addresses exist") {
                    templateOf(withRightPassword.stepData()["error"]) shouldContain "ungueltig"
                }
            }
        }

        given("a device opening channels in a loop") {
            `when`("one more channel than the per-window budget is created on one binding key") {
                repeat(channelsPerWindow) { post("/orchestrator/api/v1/app/channels") }

                val result = runCatching { post("/orchestrator/api/v1/app/channels") }

                then("the creation itself is rejected, so journeys stop being free to restart") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.TOO_MANY_REQUESTS
                }
            }
        }

        given("a Freischaltcode being guessed for a known KVNR") {
            `when`("five wrong codes are submitted, each in its own journey, then the right one") {
                repeat(5) { attempt -> submitFsc("WRONG-$attempt") }

                val withRightCode = submitFsc("VALIDCODE")

                then("further attempts fail even with the right code, without saying why") {
                    // ident-fsc is rate-limited: the FSC is a guessable credential, and a hit adopts the
                    // person's account.
                    withRightCode.next()["step"] shouldBe "input"
                    templateOf(withRightCode.stepData()["error"]) shouldContain "Freischaltcode"
                }
            }
        }
    }

    /** One lookup-login attempt in its own channel and journey. */
    private fun submitLookupPassword(email: String, password: String): Map<String, Any?> {
        val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
            .channel()["channelSessionId"] as String
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup")
            .nextRaw()["toolSessionId"] as String
        return patch(
            "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
            """{"email":"$email","password":"$password"}"""
        )
    }

    /** One ident-fsc attempt in its own channel and journey, on a device with no link yet. */
    private fun submitFsc(fsc: String): Map<String, Any?> {
        currentBindingKeyRef = "binding-" + UUID.randomUUID()
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc")
            .nextRaw()["toolSessionId"] as String
        return patch(
            "/orchestrator/api/v1/tools/$toolSessionId/ident-fsc",
            """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"$fsc"}"""
        )
    }
}
