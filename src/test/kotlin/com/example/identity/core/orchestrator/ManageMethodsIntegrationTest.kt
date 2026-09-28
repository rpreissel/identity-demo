package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Managing authentication methods on an already-authenticated channel. Shared plumbing lives in
 * IntegrationTestSupport.
 */
class ManageMethodsIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    init {
        beforeEach { stubDpopWithFakeJwk(jwkThumbprintService) }
    }

    init {
        given("a registered and authenticated account (fsc + sms + confirmed email + password)") {
            `when`("reading GET .../methods") {
                then("it matches the channel response's activeMethods") {

                // No account known yet - empty collection, not an error (docs/05-api.md #2).
                val freshChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                @Suppress("UNCHECKED_CAST")
                (get("/orchestrator/api/v1/channels/$freshChannelSessionId/methods")["methods"] as List<*>).shouldBeEmpty()

                val channelSessionId = loginAsSeededAccount()
                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                methods.methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")

                val channel = get("/orchestrator/api/v1/channels/$channelSessionId")
                @Suppress("UNCHECKED_CAST")
                channel.channel()["activeMethods"] as List<Map<String, Any?>> shouldBe methods


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email + password)") {
            `when`("starting MANAGE on an authenticated channel") {
                then("another method can be added") {

                val channelSessionId = loginAsSeededAccount()

                val started = post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                // sms and password already active from the registration; email as a login method
                // and device are offered - two candidates means a selection page, not a skip.
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val startedOptions = started.stepData()["options"] as List<String>
                // shouldContainAll (not exact) for the enrollable rest; sms/email stay explicit
                // exclusions since they're already active - that's the point of this scenario.
                startedOptions shouldContainAll listOf("enroll-email", "enroll-device", "enroll-qr")
                startedOptions shouldNotContain "enroll-sms"
                startedOptions shouldNotContain "enroll-password"
                startedOptions shouldNotContain "confirm-email"

                // One shot: the address was confirmed during registration, so activating the tool
                // completes it - there is nothing left to prove.
                val enrolled = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-email")
                // Finishes after one enrollment, whether or not a higher floor was reached: MANAGE never
                // depends on canAccountReach, unlike the identification path.
                enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val channel = get("/orchestrator/api/v1/channels/$channelSessionId")
                channel.channel()["state"] shouldBe "AUTHENTICATED"
                @Suppress("UNCHECKED_CAST")
                channel.channel()["currentAmr"] as List<String> shouldContain "password"


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email + password)") {
            `when`("starting MANAGE once sms, password and email are all active") {
                then("the last remaining candidate is offered directly") {

                // sms and password are active from the registration; email as a login method is still
                // missing (the address itself is confirmed).
                val channelSessionId = loginAsSeededAccount()
                post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-email")
                post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                val enrollQrToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-qr").nextRaw()["toolSessionId"] as String
                patch("/orchestrator/api/v1/tools/$enrollQrToolSessionId/enroll-qr", "{}")

                // kobil cannot be enrolled here (its activation needs a real SDK run, see
                // KobilBindingIntegrationTest). Switched off so this case stays about the
                // single-candidate skip.
                put("/orchestrator/admin/tools/enroll-kobil/availability/APP", """{"enabled":false,"reason":"single-candidate case"}""")

                // sms, email, password and qr are active, so enroll-device is the single remaining
                // candidate. The "nothing left" message is covered in DeviceBindingIntegrationTest.
                val started = post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                started.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-device", "step" to "enroll")


                }
            }
        }

        given("a fresh channel") {
            `when`("deactivating a method that would drop below the channel's floor") {
                then("it is rejected") {

                // sms (POSSESSION) + password (KNOWLEDGE) reach loa2 together. Deactivating the
                // password - the only KNOWLEDGE factor - would drop the account below the channel's
                // floor, so the self-lockout guard rejects it.
                val channelSessionId = identifyAndConfirmEmail(requiredAcr = "loa2")
                val enrollSmsToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
                val (smsTan, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
                }
                patch("/orchestrator/api/v1/tools/$enrollSmsToolSessionId/enroll-sms", """{"tan":"$smsTan"}""")
                enrollPassword(channelSessionId)

                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                val passwordInstanceId = methods.first { it["method"] == "password" }["id"] as String

                val exception = assertThrows<HttpClientErrorException> {
                    delete("/orchestrator/api/v1/channels/$channelSessionId/methods/$passwordInstanceId")
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT


                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email + password)") {
            `when`("deactivating a method while another still covers the floor") {
                then("it succeeds") {

                // A real registration, not a seeded login: the channel needs identification evidence of
                // its own. After logging in with sms and password, dropping sms would pull the session
                // below its own floor (409), which is the scenario below.
                val channelSessionId = registerAndAuthenticate()
                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                val smsInstanceId = methods.first { it["method"] == "sms" }["id"] as String
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_sms.enrollment", Int::class.java) shouldBe 1

                delete("/orchestrator/api/v1/channels/$channelSessionId/methods/$smsInstanceId")

                // Removing a method revokes the credential itself, not just the instance flag: the
                // phone number is gone from the owning module, while the deactivated
                // account.auth_method row stays so account deletion still walks every ref.
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_sms.enrollment", Int::class.java) shouldBe 0
                // ...and what only that credential backed is withdrawn (ADR-12): a retraction row,
                // while the claim row itself stays - the log never mutates.
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.retraction WHERE attribute_type = 'phone_number'", Int::class.java
                ) shouldBe 1
                jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.claim WHERE attribute_type = 'phone_number'", Int::class.java
                ) shouldBe 1

                // sms is a candidate again, and with the email confirmed password is one too, hence a
                // selection page rather than a skip.
                val started = post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                started.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val startedOptions = started.stepData()["options"] as List<String>
                // shouldContainAll (not exact) for the enrollable rest; email stays an explicit
                // exclusion since it's already active - that's the point of this scenario.
                startedOptions shouldContainAll listOf("enroll-sms", "enroll-email", "enroll-device", "enroll-qr")
                startedOptions shouldNotContain "enroll-password"
                startedOptions shouldNotContain "confirm-email"


                }
            }
        }

        given("a fresh channel") {
            `when`("starting MANAGE with only loa1 session evidence") {
                then("it offers email as the complementary factor for loa2") {

                // Register with fsc+sms in one continuous session (loa2), then simulate a completely
                // fresh app session on the same device: DeviceAccountLink skips straight to LOGIN via
                // auth-sms alone, never re-proving fsc, so this session's own evidence sits at loa1.
                seedRegisteredAccount()
                val loginStart = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                authenticateViaSms(newChannelSessionId)
                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterLogin.channel()["currentAcr"] shouldBe "loa1"

                // Password is the enrolled KNOWLEDGE factor complementary to SMS (POSSESSION), so
                // MANAGE can step up through existing methods without re-identification.
                val started = triggerEnrollmentStepUp(newChannelSessionId)
                started.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")
                val passwordToolSessionId = post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-password").nextRaw()["toolSessionId"] as String
                val steppedUp = patch(
                    "/orchestrator/api/v1/tools/$passwordToolSessionId/auth-password",
                    """{"password":"correct-horse-battery"}"""
                )
                steppedUp.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                @Suppress("UNCHECKED_CAST")
                val steppedUpOptions = steppedUp.stepData()["options"] as List<String>
                // shouldContainAll (not exact) for the enrollable rest; sms/email stay explicit
                // exclusions since they're already active - that's the point of this scenario.
                steppedUpOptions shouldContainAll listOf("enroll-email", "enroll-device", "enroll-qr")
                steppedUpOptions shouldNotContain "enroll-sms"
                steppedUpOptions shouldNotContain "enroll-password"
                steppedUpOptions shouldNotContain "confirm-email"

                val afterStepUp = get("/orchestrator/api/v1/channels/$newChannelSessionId")
                afterStepUp.channel()["currentAcr"] shouldBe "loa2"


                }
            }
        }

        given("a fresh channel") {
            `when`("starting MANAGE after proving only SMS") {
                then("it uses the enrolled email before offering re-identification") {

                seedRegisteredAccount()
                val loginStart = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = loginStart.channel()["channelSessionId"] as String
                authenticateViaSms(newChannelSessionId)

                val started = post("/orchestrator/api/v1/channels/$newChannelSessionId/enrollments")
                started.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")


                }
            }
        }

        given("a password that was enrolled against a confirmed address") {
            `when`("the address itself is withdrawn") {
                then("the password goes with it - nobody declared that, its own requires did") {

                // A real registration: sms and password, both against the confirmed address.
                val channelSessionId = registerAndAuthenticate()
                @Suppress("UNCHECKED_CAST")
                val before = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                before.map { it["method"] } shouldContainAll listOf("sms", "password")

                delete("/orchestrator/api/v1/channels/$channelSessionId/attributes/email")

                // enroll-password requires ClaimRequirement(EMAIL, PROVEN), and `requires` is a standing
                // precondition (ADR-24): what a credential needed to exist it needs to keep existing.
                // sms required nothing and stays.
                @Suppress("UNCHECKED_CAST")
                val after = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
                after.map { it["method"] } shouldNotContain "password"
                after.map { it["method"] } shouldContain "sms"
                // The credential row itself is gone, not merely flagged - same as any revocation.
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_password.enrollment", Int::class.java) shouldBe 0


                }
            }

            `when`("the address was never confirmed on this account") {
                then("withdrawing it is refused instead of silently revoking what required it") {

                val channelSessionId = registerAndAuthenticate()
                delete("/orchestrator/api/v1/channels/$channelSessionId/attributes/email")

                val refused = assertThrows<HttpClientErrorException> {
                    delete("/orchestrator/api/v1/channels/$channelSessionId/attributes/email")
                }
                refused.statusCode shouldBe HttpStatus.NOT_FOUND


                }
            }
        }

        given("an identified account") {
            `when`("the holder tries to withdraw an identity anchor") {
                then("it is refused - only the confirmed address is theirs to give up") {

                val channelSessionId = registerAndAuthenticate()
                fun personAnchors() = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM account.anchor WHERE attribute_type = 'person_id'", Int::class.java
                )
                personAnchors() shouldBe 1

                // "versnr" is not an attribute name: refused like the anchors, never a 500.
                listOf("person_id", "insurance_number", "restricted_id", "nect_restricted_id", "versnr").forEach { attribute ->
                    val refused = assertThrows<HttpClientErrorException> {
                        delete("/orchestrator/api/v1/channels/$channelSessionId/attributes/$attribute")
                    }
                    refused.statusCode shouldBe HttpStatus.CONFLICT
                }
                personAnchors() shouldBe 1
                jdbcTemplate.queryForObject("SELECT COUNT(*) FROM account.retraction", Int::class.java) shouldBe 0


                }
            }
        }
    }
}
