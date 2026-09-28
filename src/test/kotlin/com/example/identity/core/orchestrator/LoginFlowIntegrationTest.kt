package com.example.identity.core.orchestrator

import com.example.identity.contract.texts.templateOf
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * Logging back in on an already-registered device, device-bound and lookup-based alike.
 * Shared plumbing lives in IntegrationTestSupport.
 */
class LoginFlowIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    private fun linkedAccountsFor(bindingKeyRef: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM orchestrator.device_account_link WHERE binding_key_ref = ?", Int::class.java, bindingKeyRef
        ) ?: 0

    init {
        given("a fresh channel") {
            `when`("identifying again with the same KVNR as an already-registered account") {
                then("auth is offered instead of enrollment") {

                // First full registration: creates an account for KVNR A123456789 with an active sms method.
                seedRegisteredAccount()
                // A new channel on a different device identifies with the same KVNR.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                val identified = patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
                )

                // The reused account already reaches loa2, so nothing is left to enroll. Two candidates
                // lead to a selection page (docs/04-orchestrierung.md #1).
                identified.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                identified.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")


                }
            }
        }

        given("a fresh channel") {
            `when`("activating the same tool twice in a row") {
                then("the first tool session is cleanly orphaned") {

                seedRegisteredAccount()
                // A double client request: each activation gets its own ToolSession and TAN.
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

                val firstActivation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                val firstToolSessionId = firstActivation.nextRaw()["toolSessionId"] as String
                val secondActivation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                val secondToolSessionId = secondActivation.nextRaw()["toolSessionId"] as String
                secondToolSessionId shouldNotBe firstToolSessionId

                @Suppress("UNCHECKED_CAST")
                val firstTan = (firstActivation["demo"] as Map<String, Any?>)["tan"] as String
                val rejected = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"$firstTan"}""")
                }
                rejected.statusCode shouldBe HttpStatus.CONFLICT

                @Suppress("UNCHECKED_CAST")
                val secondTan = (secondActivation["demo"] as Map<String, Any?>)["tan"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$secondToolSessionId/auth-sms", """{"tan":"$secondTan"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")


                }
            }
        }

        given("a fresh channel") {
            `when`("one auth method is enrolled, below the channel's own ACR floor") {
                then("the device link is written immediately anyway") {

                // The channel requires loa2, so one loa1 method does not finish registration.
                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
                )
                // The address is confirmed before any enrollment is offered.
                confirmEmailIfRequested(channelSessionId)
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (tan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val afterSms = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")
                // enroll-password is offered because the address is already confirmed.
                afterSms.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                (afterSms.stepData()["options"] as List<String>) shouldContain "enroll-password"

                // Abandon below the loa2 floor. A new channel with the default loa1 floor still
                // recognizes this device via the sms method, not via ident-fsc.
                val newChannel = post("/orchestrator/api/v1/app/channels")
                newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")

                val authenticated = authenticateViaSms(newChannel.channel()["channelSessionId"] as String)
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")


                }
            }
        }

        given("a fresh channel") {
            `when`("re-identifying into an already-enrolled account on a new device") {
                then("the device link is written for that device too") {

                // Registers on binding key #1, which writes its DeviceAccountLink.
                seedRegisteredAccount()
                // Key #2 is not linked yet (new browser profile or lost key), so it falls back to ident-fsc.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val reidentified = post("/orchestrator/api/v1/app/channels")
                reidentified.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                val channelSessionId = reidentified.channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
                )

                // Ordinary auth-sms with a known account (accountId == null in the outcome). It must
                // still link key #2, or this device would need ident-fsc on every connect.
                authenticateViaSms(channelSessionId)

                // A third channel on key #2 goes straight to login. Two active methods mean a selection page.
                val thirdChannel = post("/orchestrator/api/v1/app/channels")
                thirdChannel.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                thirdChannel.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")


                }
            }
        }

        given("an account registered with a confirmed email and a password") {
            `when`("logging in via auth-password-lookup with email and password") {
                then("it authenticates into the existing account without asking to link the device again") {

                val email = registerWithEmailAndPassword()

                // intent=lookup_login forces lookup-based login although this device is already linked.
                val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val channelSessionId = loginStart.channel()["channelSessionId"] as String
                loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                // shouldContainAll, not exact: the lookup intent is preserved, not the catalog's exact lookup-tool set.
                loginStart.stepData()["options"] as List<String> shouldContainAll listOf("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup", "auth-qr-lookup")

                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String
                val authenticated = patch(
                    "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                    """{"email":"$email","password":"correct-horse-battery"}"""
                )
                // The device is already linked to this account, so no binding offer follows.
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val channel = get("/orchestrator/api/v1/channels/$channelSessionId")
                channel.channel()["state"] shouldBe "AUTHENTICATED"
                @Suppress("UNCHECKED_CAST")
                channel.channel()["currentAmr"] as List<String> shouldContain "password"

                // The existing DeviceAccountLink stays, so a FAST channel still recognizes the device.
                val nextAuto = post("/orchestrator/api/v1/app/channels")
                nextAuto.nextRaw()["context"] shouldNotBe "registration"


                }
            }
        }

        given("a fresh channel") {
            `when`("logging in via auth-sms-lookup with email and TAN") {
                then("it authenticates into the existing account") {

                // A finished registration: only a registered account is found by a lookup (ADR-46).
                val email = registerWithEmailAndPassword()

                val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val lookupChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val lookupToolSessionId = post("/orchestrator/api/v1/channels/$lookupChannelSessionId/tools/auth-sms-lookup").nextRaw()["toolSessionId"] as String

                val (loginTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-sms-lookup", """{"email":"$email"}""")
                }
                val authenticated = patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-sms-lookup", """{"tan":"$loginTan"}""")
                // Registered on this device, so no binding offer follows.
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val channel = get("/orchestrator/api/v1/channels/$lookupChannelSessionId")
                channel.channel()["state"] shouldBe "AUTHENTICATED"


                }
            }
        }

        given("an account registered with a confirmed email and a password") {
            `when`("declining the device-binding offer after a lookup login") {
                then("the device stays unlinked") {

                val email = registerWithEmailAndPassword()

                // A different, never-linked device: only the answer to the binding offer decides the link.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()

                val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val channelSessionId = loginStart.channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                    """{"email":"$email","password":"correct-horse-battery"}"""
                )

                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                linkedAccountsFor(currentBindingKeyRef) shouldBe 0

                // A FAST channel on this device therefore still has to identify.
                val nextAuto = post("/orchestrator/api/v1/app/channels")
                nextAuto.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")


                }
            }
        }

        given("a device already linked to account A") {
            `when`("a lookup login resolves a different account B on the same device") {
                then("it asks for rebind confirmation instead of overwriting the device link silently") {

                seedRegisteredAccount()
                val bindingKeyA = currentBindingKeyRef

                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val channelB = post("/orchestrator/api/v1/app/channels", """{"intent":"register","requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelB/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"B987654321","familyName":"Beispiel","givenNames":"Erika","birthDate":"1990-11-02","fsc":"ERIKA123"}"""
                )
                val emailB = confirmEmail(channelB)
                enrollSms(channelB)
                val enrollPasswordToolSessionId = post("/orchestrator/api/v1/channels/$channelB/tools/enroll-password").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$enrollPasswordToolSessionId/enroll-password",
                    """{"password":"second-account-password"}"""
                )

                currentBindingKeyRef = bindingKeyA

                val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val lookupChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val lookupToolSessionId = post("/orchestrator/api/v1/channels/$lookupChannelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String
                val prompted = patch(
                    "/orchestrator/api/v1/tools/$lookupToolSessionId/auth-password-lookup",
                    """{"email":"$emailB","password":"second-account-password"}"""
                )

                prompted.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                @Suppress("UNCHECKED_CAST")
                val prompt = prompted.stepData()["prompt"] as Map<String, Any?>
                templateOf(prompt["title"]) shouldBe "Dieses Gerät ist bereits einem anderen Konto zugeordnet"
                templateOf(prompt["confirmLabel"]) shouldBe "Gerät neu zuordnen"
                templateOf(prompt["cancelLabel"]) shouldBe "Ohne Verknüpfung fortfahren"
                prompt["destructive"] shouldBe true

                val declined = post("/orchestrator/api/v1/channels/$lookupChannelSessionId/answer", """{"answer":"decline"}""")
                declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val nextAuto = post("/orchestrator/api/v1/app/channels")
                nextAuto.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")


                }
            }
        }

        given("a fresh channel") {
            `when`("reading the channel after logging in via only one of several active methods") {
                then("activeMethods still lists the methods this session never proved") {

                // loa2 needs two factor types: sms (POSSESSION) and password (KNOWLEDGE).
                val channelSessionId =
                    post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
                )
                // The address is confirmed before any enrollment is offered.
                confirmEmailIfRequested(channelSessionId)
                val enrollSmsToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (smsTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
                enrollPassword(channelSessionId)

                val logoutPrompt = post("/orchestrator/api/v1/channels/$channelSessionId/logouts")
                logoutPrompt.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")

                val loginStart = post("/orchestrator/api/v1/app/channels")
                val loginChannelSessionId = loginStart.channel()["channelSessionId"] as String
                authenticateViaSms(loginChannelSessionId)
                // sms alone meets the default loa1 floor, so an explicit loa2 step-up asks for the
                // knowledge factor.
                post("/orchestrator/api/v1/channels/$loginChannelSessionId/step-ups", """{"requiredAcr":"loa2"}""")
                val emailActivation = post("/orchestrator/api/v1/channels/$loginChannelSessionId/tools/auth-password")
                val authEmailToolSessionId = emailActivation.nextRaw()["toolSessionId"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$authEmailToolSessionId/auth-password", """{"password":"correct-horse-battery"}""")
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val channel = get("/orchestrator/api/v1/channels/$loginChannelSessionId")
                @Suppress("UNCHECKED_CAST")
                channel.channel()["currentAmr"] as List<String> shouldContainExactlyInAnyOrder listOf("sms", "password")
                @Suppress("UNCHECKED_CAST")
                (channel.channel()["activeMethods"] as List<*>).methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")


                }
            }
        }

        given("an account registered with a confirmed email and a password") {
            `when`("logging in via auth-email-lookup with email and code") {
                then("it authenticates into the existing account") {

                // Confirming the address does not create the email method (ADR-17).
                val email = registerWithEmailAndPassword(alsoEnrollEmailMethod = true)

                val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val lookupChannelSessionId = loginStart.channel()["channelSessionId"] as String
                val lookupToolSessionId = post("/orchestrator/api/v1/channels/$lookupChannelSessionId/tools/auth-email-lookup").nextRaw()["toolSessionId"] as String

                val (loginCode, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-email-lookup", """{"email":"$email"}""")
                }
                val authenticated = patch("/orchestrator/api/v1/tools/$lookupToolSessionId/auth-email-lookup", """{"code":"$loginCode"}""")
                // Same device as the registration, so no binding offer follows.
                authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val channel = get("/orchestrator/api/v1/channels/$lookupChannelSessionId")
                channel.channel()["state"] shouldBe "AUTHENTICATED"
                @Suppress("UNCHECKED_CAST")
                channel.channel()["currentAmr"] as List<String> shouldContain "email"


                }
            }
        }

        given("a fresh channel") {
            `when`("submitting an unknown email to a lookup login") {
                then("it fails in exactly the same shape as a wrong credential") {

                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String

                // Same shape as a wrong password: no distinct error for an unknown email
                // (enumeration protection).
                val response = patch(
                    "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                    """{"email":"nobody@example.com","password":"whatever12"}"""
                )
                response.stepData()["error"].shouldNotBeNull()
                response.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password-lookup", "step" to "auth")


                }
            }
        }

        given("an account registered with a confirmed email and a password") {
            `when`("submitting an unknown vs. a known email to auth-email-lookup") {
                then("the responses are indistinguishable") {

                val knownEmail = registerWithEmailAndPassword()

                fun submit(email: String): Map<String, Any?> {
                    val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""").channel()["channelSessionId"] as String
                    val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-email-lookup").nextRaw()["toolSessionId"] as String
                    return patch("/orchestrator/api/v1/tools/$toolSessionId/auth-email-lookup", """{"email":"$email"}""").next()
                }

                // Any difference in `next` would reveal whether the address exists.
                val actualNext = submit("nobody@example.com")
                actualNext shouldBe submit(knownEmail)
                actualNext shouldBe mapOf("type" to "tool", "toolId" to "auth-email-lookup", "step" to "codeInput")


                }
            }
        }

        given("a fresh channel") {
            `when`("opening a channel with intent=lookup_login on a device that was never linked") {
                then("lookup tools are offered, not registration") {

                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                @Suppress("UNCHECKED_CAST")
                // shouldContainAll, not exact: the lookup intent is preserved, not the catalog's exact lookup-tool set.
                channelResponse.stepData()["options"] as List<String> shouldContainAll listOf("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup", "auth-qr-lookup")


                }
            }
        }
    }
}
