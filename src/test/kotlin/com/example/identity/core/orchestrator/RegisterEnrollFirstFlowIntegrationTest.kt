package com.example.identity.core.orchestrator

import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.example.identity.core.orchestrator.support.AccountFixtures
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * The REGISTER "Enrollment zuerst" experiment (docs/04-orchestrierung.md, `RegisterEnrollFirstStrategy`):
 * enrollment runs against an account with no person behind it, identification is an optional
 * closing offer. Switched on per test via the admin endpoint; `IntegrationTestSupport` resets
 * `orchestrator.feature_flag`, so it does not leak into ident-first tests.
 */
class RegisterEnrollFirstFlowIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var jwkThumbprintService: JwkThumbprintService

    @Autowired
    private lateinit var accountService: AccountService

    init {
        beforeEach {
            stubDpopWithFakeJwk(jwkThumbprintService)
            put("/orchestrator/admin/registration-order", """{"enrollFirst":true}""") shouldBe HttpStatus.OK
        }
    }

    /** The only account; each test below creates exactly one. Read via `AccountService`, not SQL. */
    private fun theAccount(): AccountProfile =
        accountService.findAccount(accountService.allAccountIds().single())!!

    init {
        given("registration order set to enroll-first, a fresh channel, APP") {
            `when`("enrolling email, then sms, declining the closing identification offer") {
                then("finishes AUTHENTICATED with no person behind the account, enrolledUnderAcr stays loa1") {

                val channelResponse = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")
                val channelSessionId = channelResponse.channel()["channelSessionId"] as String
                // Email first as the only candidate, so the run skips the selectMethod screen.
                channelResponse.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-email", "step" to "input")

                confirmEmail(channelSessionId)

                // SMS is mandatory next, still before any identification.
                val afterEmail = get("/orchestrator/api/v1/channels/$channelSessionId")
                afterEmail.next() shouldBe mapOf("type" to "tool", "toolId" to "enroll-sms", "step" to "enroll")

                enrollSms(channelSessionId)

                // After the mandatory password the account is still unidentified, so the optional
                // RE_IDENTIFY offer follows as a prompt.
                enrollPassword(channelSessionId)
                val afterSms = get("/orchestrator/api/v1/channels/$channelSessionId")
                afterSms.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                // With sms enrolled the account is set up (ADR-46): leaving the optional offer open
                // loses nothing, and the channel no longer shows REGISTERING.
                afterSms.channel()["state"] shouldBe "ANONYMOUS"

                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                declined.channel()["state"] shouldBe "AUTHENTICATED"

                val account = theAccount()
                account.personId.shouldBeNull()
                // The address is attested, not a method. The password is capped at loa1.
                account.email.shouldNotBeNull()
                account.authenticationMethods.single { it.method == "password" }.enrolledUnderAcr shouldBe "loa1"

                }
            }
        }

        given("registration order set to enroll-first, a fresh channel, APP") {
            `when`("enrolling email, then sms, then accepting the closing identification offer via ident-fsc") {
                then("the account becomes identified, but the already-enrolled email keeps its original enrolledUnderAcr") {

                val channelSessionId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                confirmEmail(channelSessionId)
                enrollSms(channelSessionId)
                enrollPassword(channelSessionId)
                get("/orchestrator/api/v1/channels/$channelSessionId").next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                // Two ident methods are registered, so a selection page comes first.
                val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                accepted.next()["type"] shouldBe "orchestrator"
                val identToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                val identified = patch(
                    "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
                )
                identified.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

                val account = theAccount()
                account.personId.shouldNotBeNull()
                // Not upgraded afterwards: the level is fixed at enrollment time
                // (docs/04-orchestrierung.md, "IAL und AAL").
                account.email.shouldNotBeNull()
                account.authenticationMethods.single { it.method == "password" }.enrolledUnderAcr shouldBe "loa1"

                }
            }
        }

        given("an account already registered enroll-first with a real person, and a second, different account") {
            `when`("the second account's closing identification offer resolves to the SAME already-registered person") {
                then("it is rejected as a conflict, the second account stays unidentified") {

                // First account: enrolls, then actually identifies via ident-fsc.
                val firstChannelId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                confirmEmail(firstChannelId)
                enrollSms(firstChannelId)
                enrollPassword(firstChannelId)
                post("/orchestrator/api/v1/channels/$firstChannelId/answer", """{"answer":"accept"}""")
                val firstIdentToolSessionId = post("/orchestrator/api/v1/channels/$firstChannelId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
                patch(
                    "/orchestrator/api/v1/tools/$firstIdentToolSessionId/ident-fsc",
                    """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
                )

                // Second account on a fresh device identifies as the same person: a merge, not supported.
                currentBindingKeyRef = "a-completely-different-binding-key"
                val secondChannelId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                confirmEmail(secondChannelId)
                enrollSms(secondChannelId)
                enrollPassword(secondChannelId)
                post("/orchestrator/api/v1/channels/$secondChannelId/answer", """{"answer":"accept"}""")
                val secondIdentToolSessionId = post("/orchestrator/api/v1/channels/$secondChannelId/tools/ident-fsc").nextRaw()["toolSessionId"] as String

                val exception = assertThrows<HttpClientErrorException> {
                    patch(
                        "/orchestrator/api/v1/tools/$secondIdentToolSessionId/ident-fsc",
                        """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
                    )
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT

                }
            }
        }

        given("an account already registered enroll-first and fully set up, on a different device") {
            `when`("confirming the SAME already-confirmed email address, with no other proof of that account") {
                then("it is rejected as a conflict, the second device never gets bound to the first account") {

                // First device: a credentialed account with no person behind it.
                val firstChannelId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                val sharedEmail = confirmEmail(firstChannelId)
                enrollSms(firstChannelId)
                enrollPassword(firstChannelId)
                post("/orchestrator/api/v1/channels/$firstChannelId/answer", """{"answer":"decline"}""")
                val firstAccountId = accountService.allAccountIds().single()

                // A different device that only knows the same email address.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val secondChannelId = (post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""")).channel()["channelSessionId"] as String
                val secondNext = get("/orchestrator/api/v1/channels/$secondChannelId").nextRaw()
                val confirmToolSessionId = (secondNext["toolSessionId"] as? String)?.takeIf { secondNext["toolId"] == "confirm-email" }
                    ?: post("/orchestrator/api/v1/channels/$secondChannelId/tools/confirm-email").nextRaw()["toolSessionId"] as String
                val (code, _) = captureMockTan {
                    patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-email", """{"email":"$sharedEmail"}""")
                }

                val exception = assertThrows<HttpClientErrorException> {
                    patch("/orchestrator/api/v1/tools/$confirmToolSessionId/confirm-email", """{"code":"$code"}""")
                }
                exception.statusCode shouldBe HttpStatus.CONFLICT

                // The first account is untouched: still the only one, with its own two methods.
                accountService.allAccountIds() shouldBe setOf(firstAccountId)
                accountService.findAccount(firstAccountId)!!.authenticationMethods.map { it.method }.toSet() shouldBe setOf("sms", "password")

                }
            }
        }

        given("a device already durably linked to somebody else's account, registering enroll-first") {
            `when`("the run finishes and the closing rebind prompt is declined") {
                then("the other account keeps both the device link and its device credential") {

                // Somebody else's account owns this physical device key.
                val otherAccountId = accountFixtures.seedAccount(bindDeviceKeyRef = currentBindingKeyRef)

                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
                confirmEmail(channelSessionId)
                enrollSms(channelSessionId)
                enrollPassword(channelSessionId)
                // The optional identification offer comes first - decline it.
                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")

                // Only now is the rebind asked. The enrollments above do not take the device on their own.
                val rebindPrompt = get("/orchestrator/api/v1/channels/$channelSessionId")
                rebindPrompt.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")

                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                declined.channel()["state"] shouldBe "AUTHENTICATED"

                linkedAccountId() shouldBe otherAccountId
                }
            }
        }

        given("a device already durably linked to somebody else's account, registering enroll-first") {
            `when`("the closing rebind prompt is accepted") {
                then("the device moves to the newly registered account") {

                val otherAccountId = accountFixtures.seedAccount(bindDeviceKeyRef = currentBindingKeyRef)

                val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
                confirmEmail(channelSessionId)
                enrollSms(channelSessionId)
                enrollPassword(channelSessionId)
                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")

                val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                accepted.channel()["state"] shouldBe "AUTHENTICATED"

                // The link moved off the seeded account onto the one this run created.
                val linked = linkedAccountId()
                linked shouldNotBe otherAccountId
                accountService.allAccountIds() shouldContain linked
                }
            }
        }
    }

    /** Which account this test's physical device key currently resolves to, if any. */
    private fun linkedAccountId(): Long? =
        jdbcTemplate.queryForList(
            "SELECT account_id FROM orchestrator.device_account_link WHERE binding_key_ref = ?",
            Long::class.java, currentBindingKeyRef
        ).firstOrNull()
}
