package com.example.identity.core.orchestrator

import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.support.AccountFixtures
import io.kotest.assertions.throwables.shouldThrow
import org.springframework.beans.factory.annotation.Autowired
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldNotContainKeys
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod

/**
 * Registration and login of a freshly identified person, via each credential type in turn.
 * Shared plumbing lives in IntegrationTestSupport.
 */
class RegistrationFlowIntegrationTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var accountService: AccountService

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    init {
        given("a fresh channel") {
            `when`("registering via ident-fsc, confirm-email, enroll-sms and enroll-password, then starting a fresh session") {
                // Channel init -> registration entry point (docs/05-api.md #2 example 1).
                val channelResponse = post("/orchestrator/api/v1/app/channels")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String

                val identActivation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc")
                val identToolSessionId = identActivation.nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15"}"""
                )
                val identified = patch("/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc", """{"fsc":"VALIDCODE"}""")

                confirmEmail(channelSessionId)
                val afterEmail = get("/orchestrator/api/v1/channels/$channelSessionId")

                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (enrollTan, afterPhone) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")
                val afterSms = get("/orchestrator/api/v1/channels/$channelSessionId")

                enrollPassword(channelSessionId)
                val finalChannel = get("/orchestrator/api/v1/channels/$channelSessionId")

                // A fresh app session on the same device (same DPoP key).
                val loginStart = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val authenticated = authenticateViaSms(newChannelSessionId)
                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")

                then("the channel starts without an account and offers identification") {
                    // No account yet, so nothing is being set up (ADR-46).
                    channelResponse.channel()["state"] shouldBe "ANONYMOUS"
                    channelResponse.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                    // shouldContainAll, not exact: identification is offered, not the catalog's exact set.
                    @Suppress("UNCHECKED_CAST")
                    (channelResponse.stepData()["options"] as List<String>) shouldContainAll listOf("ident-fsc", "ident-eid")
                    identActivation.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-fsc", "step" to "input")
                }
                then("the identification creates the account and asks for the address before any method") {
                    // The address comes first because enroll-password depends on it.
                    identified.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-email", "step" to "input")
                    identified.channel()["state"] shouldBe "REGISTERING"
                }
                then("the confirmed address unlocks the login methods, enroll-password among them") {
                    afterEmail.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                    // shouldContainAll, not exact: new catalog methods don't change this.
                    @Suppress("UNCHECKED_CAST")
                    (afterEmail.stepData()["options"] as List<String>) shouldContainAll listOf("enroll-sms", "enroll-device", "enroll-qr", "enroll-password")
                }
                then("the TAN is echoed in demo, and tool responses carry no ACR, AMR or active methods") {
                    afterPhone.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput")
                    @Suppress("UNCHECKED_CAST")
                    (afterPhone["demo"] as Map<String, Any?>)["tan"] shouldBe enrollTan
                    // docs/05-api.md #2
                    afterPhone.channel().shouldNotContainKeys("currentAcr", "currentAmr", "activeMethods")
                    enrolled.channel().shouldNotContainKeys("currentAcr", "currentAmr", "activeMethods")
                }
                then("sms alone leaves the factor-kind obligation open, so a method of another kind is offered") {
                    enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                    @Suppress("UNCHECKED_CAST")
                    (enrolled.stepData()["options"] as List<String>) shouldContainAll listOf("enroll-password", "enroll-device")
                    // sms makes the account set up (ADR-46); the open obligation belongs to the journey.
                    afterSms.channel()["state"] shouldBe "ANONYMOUS"
                }
                then("the password completes the registration with loa2 from fsc, sms and password") {
                    finalChannel.channel()["state"] shouldBe "AUTHENTICATED"
                    finalChannel.channel()["currentAcr"] shouldBe "loa2"
                    @Suppress("UNCHECKED_CAST")
                    (finalChannel.channel()["currentAmr"] as List<String>) shouldContainExactlyInAnyOrder listOf("fsc", "sms", "password")
                    (finalChannel.channel()["activeMethods"] as List<*>).methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")
                }
                then("the fresh session is recognized via the device link and offers both login methods") {
                    newChannelSessionId shouldNotBe channelSessionId
                    loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    @Suppress("UNCHECKED_CAST")
                    (loginStart.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
                then("the login via sms succeeds") {
                    authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    afterLogin.channel()["state"] shouldBe "AUTHENTICATED"
                }
            }

            `when`("a different DPoP key claims to own the channel") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                currentBindingKeyRef = "a-completely-different-binding-key"

                val result = runCatching { get("/orchestrator/api/v1/channels/$channelSessionId") }

                then("access is forbidden") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.FORBIDDEN
                }
            }
        }

        given("a client with a DPoP proof") {
            `when`("creating a channel") {
                val response = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/app/channels", HttpMethod.POST,
                    HttpEntity("""{"availableTools":["ident-fsc"]}""", headers()), mapType
                )

                then("the response is 201 with a Location header pointing at it") {
                    response.statusCode shouldBe HttpStatus.CREATED
                    val channelSessionId = response.body!!.channel()["channelSessionId"] as String
                    response.headers.location.toString() shouldBe "http://localhost:$port/orchestrator/api/v1/channels/$channelSessionId"
                }
            }
        }

        given("a client without a DPoP header") {
            `when`("it creates a channel") {
                val headersWithoutDpop = HttpHeaders().apply { set("Content-Type", "application/json") }
                val result = runCatching {
                    restTemplate.exchange(
                        "http://localhost:$port/orchestrator/api/v1/app/channels", HttpMethod.POST,
                        HttpEntity("{}", headersWithoutDpop), mapType
                    )
                }

                then("it is rejected as unauthorized before any controller logic runs") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.UNAUTHORIZED
                }
            }
        }

        given("an identified channel with a confirmed address") {
            `when`("activating a tool") {
                val channelSessionId = identifyAndConfirmEmail()
                val response = restTemplate.exchange(
                    "http://localhost:$port/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms",
                    HttpMethod.POST, HttpEntity("{}", headers()), mapType
                )

                then("the response is 201 with a Location header pointing at the tool resource") {
                    response.statusCode shouldBe HttpStatus.CREATED
                    val toolSessionId = response.body!!.nextRaw()["toolSessionId"] as String
                    response.headers.location.toString() shouldBe "http://localhost:$port/orchestrator/api/v1/tools/$toolSessionId/enroll-sms"
                }
            }

            `when`("submitting an invalid phone number to enroll-sms") {
                val channelSessionId = identifyAndConfirmEmail()
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String

                val result = runCatching {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"not-a-number"}""")
                }

                then("bad tool input is rejected as bad request") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            `when`("the client resumes mid enroll-sms via GET and then submits the TAN sent before") {
                // Stop right after phoneNumber was submitted, with the TAN already sent.
                val channelSessionId = identifyAndConfirmEmail()
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (enrollTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                // Resume via GET, not reactivation (docs/05-api.md #2).
                val resumed = get("/orchestrator/api/v1/channels/$channelSessionId")
                val enrolled = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$enrollTan"}""")

                then("the resume returns the running tool session that already awaits the TAN") {
                    resumed.nextRaw() shouldBe
                        mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "tanInput", "toolSessionId" to enrollToolSessionId)
                }
                then("the TAN from before the resume still confirms the session") {
                    // A method of another factor kind is still an outstanding Required Action.
                    enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                }
            }
        }

        given("an identified channel without a confirmed address") {
            `when`("activating enroll-password") {
                // Bypass attempt without a confirmed email. validateActivation only checks the category,
                // so ToolJourneyService must enforce the requires precondition itself.
                val channelSessionId = identify()

                val result = runCatching { post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-password") }

                then("it is rejected as conflict") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
            }
        }

        given("a device already linked to an account") {
            `when`("opening a channel with intent=register") {
                seedRegisteredAccount()
                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")

                then("a fresh registration starts instead of login") {
                    // Not the linked account: the fresh registration has none yet (ADR-46).
                    channelResponse.channel()["state"] shouldBe "ANONYMOUS"
                    channelResponse.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                }
            }
        }

        given("a channel that already identified as one real, credentialed account, then declined its only auth method") {
            `when`("re-identifying via ident-fsc as a SECOND, different real, credentialed account") {
                val first = accountFixtures.seedAccount(
                    kvnr = "A123456789", name = "Muster", vorname = "Max",
                    email = "max.muster@example.com",
                    methods = listOf(AccountFixtures.Method.Sms())
                )
                accountFixtures.seedAccount(
                    kvnr = "B987654321", name = "Beispiel", vorname = "Erika",
                    email = "erika.beispiel@example.com",
                    methods = listOf(AccountFixtures.Method.Sms())
                )

                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
                val afterFirstIdent = reIdentifyViaFsc(channelSessionId)
                val authSmsToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms").nextRaw()["toolSessionId"] as String
                // Declining leads back to identification, with Max's account already bound to the journey.
                val afterDecline = delete("/orchestrator/api/v1/tools/$authSmsToolSessionId/auth-sms")
                val secondIdentToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

                val result = runCatching {
                    patch(
                        "/orchestrator/api/v1/tools/$secondIdentToolSessionId/ident-fsc",
                        """{"kvnr":"B987654321","familyName":"Beispiel","givenNames":"Erika","birthDate":"1990-11-02","fsc":"ERIKA123"}"""
                    )
                }

                then("the first identification offers the first account's auth, and declining it offers identification again") {
                    // Max's account already has sms and a confirmed email, so auth is offered.
                    afterFirstIdent.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
                    @Suppress("UNCHECKED_CAST")
                    (afterDecline.stepData()["options"] as List<String>) shouldContainAll listOf("ident-fsc", "ident-eid")
                }
                then("the second identification is rejected as a conflict") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
                then("the first account is untouched") {
                    accountService.findAccount(first)!!.activeAuthenticationMethods.map { it.method }.toSet() shouldBe setOf("sms")
                }
            }
        }
    }
}
