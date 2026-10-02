package com.example.identity.core.orchestrator

import com.example.identity.contract.texts.templateOf
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * Logging back in on an already-registered device, device-bound and lookup-based alike.
 * Shared plumbing lives in IntegrationTestSupport.
 */
class LoginFlowIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private val authenticatedNext = mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
    private val lookupTools = listOf("auth-sms-lookup", "auth-password-lookup", "auth-email-lookup", "auth-qr-lookup")

    private fun linkedAccountsFor(bindingKeyRef: String): Int =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM orchestrator.device_account_link WHERE binding_key_ref = ?", Int::class.java, bindingKeyRef
        ) ?: 0

    private fun lookupChannel(): String =
        post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""").channel()["channelSessionId"] as String

    /** Runs auth-password-lookup on a fresh lookup channel; returns the channel and the tool's answer. */
    private fun passwordLookup(email: String, password: String): Pair<String, Map<String, Any?>> {
        val channelSessionId = lookupChannel()
        val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String
        return channelSessionId to patch(
            "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
            """{"email":"$email","password":"$password"}"""
        )
    }

    init {
        given("a registered account and a new device that is not linked yet") {
            `when`("the new device re-identifies with the same KVNR, logs in via sms and opens another channel") {
                // Key #1 is linked by the seeded registration. Key #2 is a new browser profile or a lost key.
                seedRegisteredAccount()
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val reidentifying = post("/orchestrator/api/v1/app/channels")
                val channelSessionId = reidentifying.channel()["channelSessionId"] as String
                val identified = reIdentifyViaFsc(channelSessionId)
                // Ordinary auth-sms with a known account (accountId == null in the outcome).
                authenticateViaSms(channelSessionId)

                val thirdChannel = post("/orchestrator/api/v1/app/channels")

                then("the unlinked device falls back to identification") {
                    reidentifying.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                }
                then("auth is offered instead of enrollment, as a selection page of both methods") {
                    // The reused account already reaches loa2, so nothing is left to enroll
                    // (docs/04-orchestrierung.md #1).
                    identified.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    @Suppress("UNCHECKED_CAST")
                    (identified.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
                then("the login links the new device too, so its next channel goes straight to login") {
                    // Otherwise this device would need ident-fsc on every connect.
                    thirdChannel.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    @Suppress("UNCHECKED_CAST")
                    (thirdChannel.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
            }
        }

        given("a registered account on this device") {
            `when`("the same tool is activated twice in a row and both TANs are entered") {
                seedRegisteredAccount()
                // A double client request: each activation gets its own ToolSession and TAN.
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val firstActivation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                val secondActivation = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms")
                val firstToolSessionId = firstActivation.nextRaw()["toolSessionId"] as String
                val secondToolSessionId = secondActivation.nextRaw()["toolSessionId"] as String

                @Suppress("UNCHECKED_CAST")
                val firstTan = (firstActivation["demo"] as Map<String, Any?>)["tan"] as String
                val first = runCatching { patch("/orchestrator/api/v1/tools/$firstToolSessionId/auth-sms", """{"tan":"$firstTan"}""") }
                @Suppress("UNCHECKED_CAST")
                val secondTan = (secondActivation["demo"] as Map<String, Any?>)["tan"] as String
                val authenticated = patch("/orchestrator/api/v1/tools/$secondToolSessionId/auth-sms", """{"tan":"$secondTan"}""")

                then("each activation gets its own tool session") {
                    secondToolSessionId shouldNotBe firstToolSessionId
                }
                then("the first tool session is cleanly orphaned") {
                    shouldThrow<HttpClientErrorException> { first.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
                then("the second one authenticates") {
                    authenticated.next() shouldBe authenticatedNext
                }
            }

            `when`("a new channel logs in via sms and steps up to loa2 with the password") {
                seedRegisteredAccount()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                authenticateViaSms(channelSessionId)
                // sms alone meets the default loa1 floor, so an explicit loa2 step-up asks for the
                // knowledge factor.
                post("/orchestrator/api/v1/channels/$channelSessionId/step-ups", """{"requiredAcr":"loa2"}""")
                val authenticated = authenticateViaPassword(channelSessionId)
                val channel = get("/orchestrator/api/v1/channels/$channelSessionId").channel()

                then("the step-up authenticates") {
                    authenticated.next() shouldBe authenticatedNext
                }
                then("currentAmr holds both proofs, and activeMethods lists the account's methods") {
                    @Suppress("UNCHECKED_CAST")
                    (channel["currentAmr"] as List<String>) shouldContainExactlyInAnyOrder listOf("sms", "password")
                    (channel["activeMethods"] as List<*>).methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")
                }
            }
        }

        given("a channel that demands loa2") {
            `when`("one auth method is enrolled below that floor and a new loa1 channel is opened") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"requiredAcr":"loa2"}""").channel()["channelSessionId"] as String
                reIdentifyViaFsc(channelSessionId)
                // The address is confirmed before any enrollment is offered.
                confirmEmailIfRequested(channelSessionId)
                val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (tan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                val afterSms = patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")

                // Abandon below the loa2 floor.
                val newChannel = post("/orchestrator/api/v1/app/channels")
                val authenticated = authenticateViaSms(newChannel.channel()["channelSessionId"] as String)

                then("one loa1 method does not finish the registration, enroll-password is offered next") {
                    afterSms.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                    @Suppress("UNCHECKED_CAST")
                    (afterSms.stepData()["options"] as List<String>) shouldContain "enroll-password"
                }
                then("the device link was written anyway: the new channel recognizes the device via sms") {
                    newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-sms", "step" to "auth")
                    authenticated.next() shouldBe authenticatedNext
                }
            }
        }

        given("an account registered with a confirmed email and a password, on this device") {
            `when`("a lookup login forced on the linked device proves email and password") {
                val email = registerWithEmailAndPassword()

                // intent=lookup_login forces lookup-based login although this device is already linked.
                val loginStart = post("/orchestrator/api/v1/app/channels", """{"intent":"lookup_login"}""")
                val channelSessionId = loginStart.channel()["channelSessionId"] as String
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-password-lookup").nextRaw()["toolSessionId"] as String
                val authenticated = patch(
                    "/orchestrator/api/v1/tools/$toolSessionId/auth-password-lookup",
                    """{"email":"$email","password":"correct-horse-battery"}"""
                )
                val channel = get("/orchestrator/api/v1/channels/$channelSessionId").channel()
                val nextAuto = post("/orchestrator/api/v1/app/channels")

                then("the lookup tools are offered") {
                    loginStart.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    // shouldContainAll, not exact: the lookup intent is preserved, not the catalog's exact lookup-tool set.
                    @Suppress("UNCHECKED_CAST")
                    (loginStart.stepData()["options"] as List<String>) shouldContainAll lookupTools
                }
                then("it authenticates into the existing account without asking to link the device again") {
                    authenticated.next() shouldBe authenticatedNext
                    channel["state"] shouldBe "AUTHENTICATED"
                    @Suppress("UNCHECKED_CAST")
                    (channel["currentAmr"] as List<String>) shouldContain "password"
                }
                then("the existing device link stays, so a FAST channel still recognizes the device") {
                    nextAuto.nextRaw()["context"] shouldNotBe "registration"
                }
            }

            `when`("a lookup login proves email and TAN via auth-sms-lookup") {
                // A finished registration: only a registered account is found by a lookup (ADR-46).
                val email = registerWithEmailAndPassword()
                val channelSessionId = lookupChannel()
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-sms-lookup").nextRaw()["toolSessionId"] as String
                val (loginTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms-lookup", """{"email":"$email"}""")
                }

                val authenticated = patch("/orchestrator/api/v1/tools/$toolSessionId/auth-sms-lookup", """{"tan":"$loginTan"}""")
                val state = get("/orchestrator/api/v1/channels/$channelSessionId").channel()["state"]

                then("it authenticates into the existing account, with no binding offer on its own device") {
                    authenticated.next() shouldBe authenticatedNext
                    state shouldBe "AUTHENTICATED"
                }
            }

            `when`("a lookup login proves email and code via auth-email-lookup") {
                // Confirming the address does not create the email method (ADR-17).
                val email = registerWithEmailAndPassword(alsoEnrollEmailMethod = true)
                val channelSessionId = lookupChannel()
                val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-email-lookup").nextRaw()["toolSessionId"] as String
                val (loginCode, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$toolSessionId/auth-email-lookup", """{"email":"$email"}""")
                }

                val authenticated = patch("/orchestrator/api/v1/tools/$toolSessionId/auth-email-lookup", """{"code":"$loginCode"}""")
                val channel = get("/orchestrator/api/v1/channels/$channelSessionId").channel()

                then("it authenticates into the existing account, with no binding offer on its own device") {
                    authenticated.next() shouldBe authenticatedNext
                    channel["state"] shouldBe "AUTHENTICATED"
                    @Suppress("UNCHECKED_CAST")
                    (channel["currentAmr"] as List<String>) shouldContain "email"
                }
            }

            `when`("a lookup login on a never-linked device succeeds and the device-binding offer is declined") {
                val email = registerWithEmailAndPassword()
                // A different, never-linked device: only the answer to the binding offer decides the link.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val (channelSessionId, _) = passwordLookup(email, "correct-horse-battery")

                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                val nextAuto = post("/orchestrator/api/v1/app/channels")

                then("the device stays unlinked") {
                    linkedAccountsFor(currentBindingKeyRef) shouldBe 0
                }
                then("a FAST channel on this device therefore still has to identify") {
                    nextAuto.next() shouldBe mapOf("type" to "orchestrator", "context" to "registration", "step" to "selectIdentificationMethod")
                }
            }

            `when`("an unknown email and a known email with a wrong password are each submitted to auth-password-lookup") {
                val email = registerWithEmailAndPassword()

                val (_, unknown) = passwordLookup("nobody@example.com", "whatever12")
                val (_, wrongPassword) = passwordLookup(email, "whatever12")

                then("the unknown email fails as a retryable attempt") {
                    templateOf(unknown.stepData()["error"]) shouldBe "E-Mail oder Passwort ungueltig"
                    unknown.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password-lookup", "step" to "auth")
                }
                then("in exactly the same shape as the wrong password - no distinct error for an unknown email") {
                    // Enumeration protection.
                    unknown.next() shouldBe wrongPassword.next()
                    unknown["stepData"] shouldBe wrongPassword["stepData"]
                }
            }

            `when`("an unknown and the known email are each submitted to auth-email-lookup") {
                val knownEmail = registerWithEmailAndPassword()

                fun submit(email: String): Map<String, Any?> {
                    val channelSessionId = lookupChannel()
                    val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-email-lookup").nextRaw()["toolSessionId"] as String
                    return patch("/orchestrator/api/v1/tools/$toolSessionId/auth-email-lookup", """{"email":"$email"}""").next()
                }
                val unknownNext = submit("nobody@example.com")
                val knownNext = submit(knownEmail)

                then("the responses are indistinguishable - any difference would reveal whether the address exists") {
                    unknownNext shouldBe knownNext
                    unknownNext shouldBe mapOf("type" to "tool", "toolId" to "auth-email-lookup", "step" to "codeInput")
                }
            }
        }

        given("a device already linked to account A, and a second account B") {
            `when`("a lookup login on that device resolves B, and the rebind offer is declined") {
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
                enrollPassword(channelB, password = "second-account-password")

                currentBindingKeyRef = bindingKeyA
                val (lookupChannelSessionId, prompted) = passwordLookup(emailB, "second-account-password")
                val declined = post("/orchestrator/api/v1/channels/$lookupChannelSessionId/answer", """{"answer":"decline"}""")
                val nextAuto = post("/orchestrator/api/v1/app/channels")

                then("it asks for rebind confirmation instead of overwriting the device link silently") {
                    prompted.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                    @Suppress("UNCHECKED_CAST")
                    val prompt = prompted.stepData()["prompt"] as Map<String, Any?>
                    templateOf(prompt["title"]) shouldBe "Dieses Gerät ist bereits einem anderen Konto zugeordnet"
                    templateOf(prompt["confirmLabel"]) shouldBe "Gerät neu zuordnen"
                    templateOf(prompt["cancelLabel"]) shouldBe "Ohne Verknüpfung fortfahren"
                    prompt["destructive"] shouldBe true
                }
                then("declining still finishes the login") {
                    declined.next() shouldBe authenticatedNext
                }
                then("the device stays with A: a FAST channel goes to A's login") {
                    nextAuto.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                }
            }
        }
    }
}
