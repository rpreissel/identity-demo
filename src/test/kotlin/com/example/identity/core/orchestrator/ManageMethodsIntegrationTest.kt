package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.http.HttpStatus
import java.util.UUID
import org.springframework.web.client.HttpClientErrorException

/**
 * Managing authentication methods on an already-authenticated channel. Shared plumbing lives in
 * IntegrationTestSupport.
 */
class ManageMethodsIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun methodsOf(channelSessionId: String): List<Map<String, Any?>> =
        get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>

    private fun startManage(channelSessionId: String): Map<String, Any?> =
        post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.options(): List<String> = stepData()["options"] as List<String>

    private fun countOf(sql: String): Int = jdbcTemplate.queryForObject(sql, Int::class.java)!!

    init {
        given("a fresh channel with no account known yet") {
            `when`("reading GET .../methods") {
                val freshChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val methods = methodsOf(freshChannelSessionId)

                then("it answers an empty collection, not an error (docs/05-api.md #3a)") {
                    methods.shouldBeEmpty()
                }
            }
        }

        given("a registered and authenticated account (fsc + sms + confirmed email + password)") {
            `when`("reading GET .../methods") {
                val channelSessionId = loginAsSeededAccount()
                val methods = methodsOf(channelSessionId)
                val channel = get("/orchestrator/api/v1/channels/$channelSessionId")

                then("it lists the active methods") {
                    methods.methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")
                }
                then("it matches the channel response's activeMethods") {
                    @Suppress("UNCHECKED_CAST")
                    channel.channel()["activeMethods"] as List<Map<String, Any?>> shouldBe methods
                }
            }

            `when`("starting MANAGE on an authenticated channel and adding email as a login method") {
                val channelSessionId = loginAsSeededAccount()
                val started = startManage(channelSessionId)
                // One shot: the address was confirmed during registration, so activating the tool
                // completes it - there is nothing left to prove.
                val enrolled = post("/tools/api/enroll-email/v1?channel=$channelSessionId")
                val channel = get("/orchestrator/api/v1/channels/$channelSessionId").channel()

                then("a selection page offers what is not active yet") {
                    // email as a login method and device are offered - two candidates means a
                    // selection page, not a skip.
                    started.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                    // shouldContainAll (not exact) for the enrollable rest; sms/password stay explicit
                    // exclusions since they're already active - that's the point of this scenario.
                    started.options() shouldContainAll listOf("enroll-email", "enroll-device", "enroll-qr")
                    started.options() shouldNotContain "enroll-sms"
                    started.options() shouldNotContain "enroll-password"
                    started.options() shouldNotContain "confirm-email"
                }
                then("MANAGE finishes after one enrollment") {
                    // Whether or not a higher floor was reached: MANAGE never depends on
                    // canAccountReach, unlike the identification path.
                    enrolled.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                }
                then("the channel stays authenticated with its proofs") {
                    channel["state"] shouldBe "AUTHENTICATED"
                    @Suppress("UNCHECKED_CAST")
                    channel["currentAmr"] as List<String> shouldContain "password"
                }
            }

            `when`("starting MANAGE once sms, password, email and qr are all active") {
                val channelSessionId = loginAsSeededAccount()
                startManage(channelSessionId)
                post("/tools/api/enroll-email/v1?channel=$channelSessionId")
                startManage(channelSessionId)
                val enrollQrToolSessionId = post("/tools/api/enroll-qr/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
                patch("/tools/api/enroll-qr/v1/$enrollQrToolSessionId", "{}")

                // kobil cannot be enrolled here (its activation needs a real SDK run, see
                // KobilBindingIntegrationTest). Switched off so this case stays about the
                // single-candidate skip.
                put("/orchestrator/admin/tools/enroll-kobil@1/availability/APP", """{"enabled":false,"reason":"single-candidate case"}""")

                val started = startManage(channelSessionId)

                then("the last remaining candidate, enroll-device, is offered directly") {
                    // The "nothing left" message is covered in DeviceBindingIntegrationTest.
                    started.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-device", "step" to "enroll")
                }
            }

            `when`("deactivating sms while password still covers the floor") {
                // A real registration, not a seeded login: the channel needs identification evidence of
                // its own. After logging in with sms and password, dropping sms would pull the session
                // below its own floor and be refused (409).
                val channelSessionId = registerAndAuthenticate()
                val smsInstanceId = methodsOf(channelSessionId).first { it["method"] == "sms" }["id"] as String
                val smsEnrollmentsBefore = countOf("SELECT COUNT(*) FROM auth_sms.enrollment")

                delete("/orchestrator/api/v1/channels/$channelSessionId/methods/$smsInstanceId")
                val started = startManage(channelSessionId)

                then("the credential itself is revoked, not just the instance flag") {
                    // The phone number is gone from the owning module, while the deactivated
                    // account.auth_method row stays so account deletion still walks every ref.
                    smsEnrollmentsBefore shouldBe 1
                    countOf("SELECT COUNT(*) FROM auth_sms.enrollment") shouldBe 0
                }
                then("what only that credential backed is withdrawn by a retraction, the claim itself stays (ADR-12)") {
                    countOf("SELECT COUNT(*) FROM account.retraction WHERE attribute_type = 'phone_number'") shouldBe 1
                    countOf("SELECT COUNT(*) FROM account.claim WHERE attribute_type = 'phone_number'") shouldBe 1
                }
                then("sms is a candidate again, beside the rest that is not active") {
                    // With the email confirmed password would be one too, hence a selection page.
                    started.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                    // shouldContainAll (not exact) for the enrollable rest; password stays an explicit
                    // exclusion since it's still active - that's the point of this scenario.
                    started.options() shouldContainAll listOf("enroll-sms", "enroll-email", "enroll-device", "enroll-qr")
                    started.options() shouldNotContain "enroll-password"
                    started.options() shouldNotContain "confirm-email"
                }
            }
        }

        given("a registered account on this device, logged in on a fresh channel via sms alone") {
            `when`("starting MANAGE with only loa1 session evidence and stepping up through the enrolled password") {
                // DeviceAccountLink skips straight to LOGIN via auth-sms alone, never re-proving fsc,
                // so this session's own evidence sits at loa1.
                seedRegisteredAccount()
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                authenticateViaSms(channelSessionId)
                val acrAfterLogin = get("/orchestrator/api/v1/channels/$channelSessionId").channel()["currentAcr"]

                val started = triggerEnrollmentStepUp(channelSessionId)
                val steppedUp = authenticateViaPassword(channelSessionId)
                val acrAfterStepUp = get("/orchestrator/api/v1/channels/$channelSessionId").channel()["currentAcr"]

                then("the session starts at loa1") {
                    acrAfterLogin shouldBe "loa1"
                }
                then("the step-up goes through the password, without re-identification") {
                    // Password is the enrolled KNOWLEDGE factor complementary to SMS (POSSESSION).
                    started.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-password", "step" to "auth")
                }
                then("after the step-up, what can still be enrolled is offered") {
                    steppedUp.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                    // shouldContainAll (not exact) for the enrollable rest; sms/password stay explicit
                    // exclusions since they're already active - that's the point of this scenario.
                    steppedUp.options() shouldContainAll listOf("enroll-email", "enroll-device", "enroll-qr")
                    steppedUp.options() shouldNotContain "enroll-sms"
                    steppedUp.options() shouldNotContain "enroll-password"
                    steppedUp.options() shouldNotContain "confirm-email"
                    acrAfterStepUp shouldBe "loa2"
                }
            }
        }

        given("a password that was enrolled against a confirmed address") {
            `when`("the address itself is withdrawn") {
                // A real registration: sms and password, both against the confirmed address.
                val channelSessionId = registerAndAuthenticate()
                val before = methodsOf(channelSessionId).map { it["method"] }

                delete("/orchestrator/api/v1/channels/$channelSessionId/attributes/email")
                val after = methodsOf(channelSessionId).map { it["method"] }

                then("the password goes with it - nobody declared that, its own requires did") {
                    // enroll-password requires ClaimRequirement(EMAIL, PROVEN), and `requires` is a standing
                    // precondition (ADR-24): what a credential needed to exist it needs to keep existing.
                    before shouldContainAll listOf("sms", "password")
                    after shouldNotContain "password"
                }
                then("sms required nothing and stays") {
                    after shouldContain "sms"
                }
                then("the password credential row itself is gone, as with any revocation") {
                    countOf("SELECT COUNT(*) FROM auth_password.enrollment") shouldBe 0
                }
            }

            `when`("the address is withdrawn a second time") {
                val channelSessionId = registerAndAuthenticate()
                delete("/orchestrator/api/v1/channels/$channelSessionId/attributes/email")

                val result = runCatching { delete("/orchestrator/api/v1/channels/$channelSessionId/attributes/email") }

                then("there is no confirmed address left, so it is not found") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }
        }

        given("an authenticated account changing a method in place") {
            fun changes(channelSessionId: String, methodInstanceId: String) =
                post("/orchestrator/api/v1/channels/$channelSessionId/methods/$methodInstanceId/changes")

            `when`("changing the password") {
                val channelSessionId = loginAsSeededAccount()
                val before = methodsOf(channelSessionId)
                val passwordId = before.first { it["method"] == "password" }["id"] as String

                val started = changes(channelSessionId, passwordId)
                val activated = post("/tools/api/enroll-password/v1?channel=$channelSessionId")
                val untilDone = methodsOf(channelSessionId)
                val toolSessionId = activated.nextRaw()["toolSessionId"] as String
                val completed = patch("/tools/api/enroll-password/v1/$toolSessionId", """{"password":"another-correct-horse"}""")
                val after = methodsOf(channelSessionId)

                then("the list says which methods can be changed") {
                    before.associate { it["method"] to it["changeable"] } shouldBe mapOf("sms" to true, "password" to true)
                }
                then("the password enrollment is the one tool on offer, though the method is active") {
                    started.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-password", "step" to "enroll")
                }
                then("the tool says that it replaces the active password") {
                    activated.stepData()["kind"] shouldBe "enroll-password"
                    activated.stepData()["replaces"] shouldBe true
                }
                then("the old entry stays until the new one is complete") {
                    untilDone shouldBe before
                }
                then("the new password replaces it: one active entry, a new id") {
                    completed.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    after.methodNames() shouldContainExactlyInAnyOrder listOf("sms", "password")
                    after.first { it["method"] == "password" }["id"] shouldNotBe passwordId
                }
            }

            `when`("the session's proofs age past loa2 while the new password is typed") {
                val channelSessionId = loginAsSeededAccount()
                val before = methodsOf(channelSessionId).first { it["method"] == "password" }
                changes(channelSessionId, before["id"] as String)
                val toolSessionId = post("/tools/api/enroll-password/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
                ageProofs(UUID.fromString(channelSessionId), minutes = 40)

                patch("/tools/api/enroll-password/v1/$toolSessionId", """{"password":"another-correct-horse"}""")
                val after = methodsOf(channelSessionId).first { it["method"] == "password" }

                then("the new password is written under the level the change was admitted at, not the aged one") {
                    before["enrolledUnderAcr"] shouldBe "loa2"
                    after["enrolledUnderAcr"] shouldBe "loa2"
                    after["id"] shouldNotBe before["id"]
                }
            }

            `when`("the change is abandoned in the tool") {
                val channelSessionId = loginAsSeededAccount()
                val before = methodsOf(channelSessionId)
                val passwordId = before.first { it["method"] == "password" }["id"] as String
                changes(channelSessionId, passwordId)
                post("/tools/api/enroll-password/v1?channel=$channelSessionId")

                val abandoned = delete("/orchestrator/api/v1/channels/$channelSessionId/journey")
                val after = methodsOf(channelSessionId)

                then("the channel is back at AUTHENTICATED and the old password is still the active one") {
                    abandoned.channel()["state"] shouldBe "AUTHENTICATED"
                    after shouldBe before
                }
            }

            `when`("the instance is not active") {
                val channelSessionId = loginAsSeededAccount()
                val result = runCatching { changes(channelSessionId, UUID.randomUUID().toString()) }

                then("it is not found") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }

            `when`("the method cannot be changed (email has no credential of its own)") {
                val channelSessionId = loginAsSeededAccount()
                startManage(channelSessionId)
                post("/tools/api/enroll-email/v1?channel=$channelSessionId")
                val email = methodsOf(channelSessionId).first { it["method"] == "email" }
                val result = runCatching { changes(channelSessionId, email["id"] as String) }

                then("the list says so and the call is refused") {
                    email["changeable"] shouldBe false
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
            }
        }

        given("an authenticated account whose proofs are ten minutes old") {
            `when`("deactivating sms") {
                val channelSessionId = registerAndAuthenticate()
                val smsInstanceId = methodsOf(channelSessionId).first { it["method"] == "sms" }["id"] as String
                ageProofs(UUID.fromString(channelSessionId), minutes = 10)

                val asked = delete("/orchestrator/api/v1/channels/$channelSessionId/methods/$smsInstanceId")
                val stillActive = methodsOf(channelSessionId).methodNames()
                val confirmed = authenticateViaPassword(channelSessionId)
                val activeAfterwards = methodsOf(channelSessionId).methodNames()

                then("a fresh confirmation of any active method is asked for first, nothing is removed yet") {
                    asked.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    asked.options() shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                    stillActive shouldContain "sms"
                }
                then("the confirmation carries out the removal, without a second call") {
                    confirmed.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    activeAfterwards shouldNotContain "sms"
                }
            }

            `when`("adding a method") {
                val channelSessionId = registerAndAuthenticate()
                ageProofs(UUID.fromString(channelSessionId), minutes = 10)

                val asked = startManage(channelSessionId)
                val confirmed = authenticateViaPassword(channelSessionId)

                then("a fresh confirmation is asked for first") {
                    asked.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                }
                then("the confirmation leads on to the enrollment offer") {
                    confirmed.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
                }
            }

            `when`("the confirmation is abandoned") {
                val channelSessionId = registerAndAuthenticate()
                val smsInstanceId = methodsOf(channelSessionId).first { it["method"] == "sms" }["id"] as String
                ageProofs(UUID.fromString(channelSessionId), minutes = 10)
                delete("/orchestrator/api/v1/channels/$channelSessionId/methods/$smsInstanceId")

                val abandoned = delete("/orchestrator/api/v1/channels/$channelSessionId/journey")
                val activeAfterwards = methodsOf(channelSessionId).methodNames()

                then("the channel is back at AUTHENTICATED and sms is still active") {
                    abandoned.channel()["state"] shouldBe "AUTHENTICATED"
                    activeAfterwards shouldContain "sms"
                }
            }
        }
    }
}
