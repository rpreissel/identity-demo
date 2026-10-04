package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.support.AccountFixtures
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.assertions.throwables.shouldThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.util.UUID

/**
 * The REGISTER "Enrollment zuerst" experiment (docs/journeys/register-enroll-first.md, `RegisterEnrollFirstStrategy`):
 * enrollment runs against an account with no person behind it, identification is an optional
 * closing offer. Switched on per test via the admin endpoint; `IntegrationTestSupport` resets
 * `orchestrator.feature_flag`, so it does not leak into ident-first tests.
 */
class RegisterEnrollFirstFlowIntegrationTest : IntegrationTestSupport() {

    @Autowired
    private lateinit var accountService: AccountService

    init {
        beforeScenario {
            stubDpopWithFakeJwk()
            put("/orchestrator/admin/registration-order", """{"enrollFirst":true}""") shouldBe HttpStatus.OK
        }
    }

    /** The only account; each test below creates exactly one. Read via `AccountService`, not SQL. */
    private fun theAccount(): AccountProfile =
        accountService.findAccount(accountService.allAccountIds().single())!!

    /** Runs the enroll-first registration up to the closing identification offer; returns the channel. */
    private fun enrolledUpToIdentificationOffer(): String {
        val channelSessionId = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
        confirmEmail(channelSessionId)
        enrollSms(channelSessionId)
        enrollPassword(channelSessionId)
        return channelSessionId
    }

    private fun identifyAsMax(channelSessionId: String): Map<String, Any?> {
        val identToolSessionId = post("/tools/api/ident-fsc/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
        return patch(
            "/tools/api/ident-fsc/v1/$identToolSessionId",
            """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
        )
    }

    init {
        // The order of the steps is the strategy's (RegisterEnrollFirstStrategyTest); here the run end to end.
        given("registration order set to enroll-first, a fresh channel, APP") {
            `when`("enrolling email, sms and password, then declining the closing identification offer") {
                val channelSessionId = enrolledUpToIdentificationOffer()
                val beforeOffer = get("/orchestrator/api/v1/channels/$channelSessionId")

                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")

                then("with sms enrolled the account is set up, so the channel no longer shows REGISTERING (ADR-46)") {
                    beforeOffer.channel()["state"] shouldBe "ANONYMOUS"
                }
                then("the run finishes AUTHENTICATED") {
                    declined.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    declined.channel()["state"] shouldBe "AUTHENTICATED"
                }
                then("there is no person behind the account, the address is attested, and the password stays at loa1") {
                    val account = theAccount()
                    account.personId.shouldBeNull()
                    account.email.shouldNotBeNull()
                    account.authenticationMethods.single { it.method == "password" }.enrolledUnderAcr shouldBe "loa1"
                }
            }

            `when`("enrolling email, sms and password, then accepting the closing identification offer via ident-fsc") {
                val channelSessionId = enrolledUpToIdentificationOffer()

                val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")
                val identified = identifyAsMax(channelSessionId)

                then("two ident methods are registered, so a selection page comes first") {
                    accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                }
                then("the identification finishes the run") {
                    identified.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                }
                then("the account becomes identified, but the password keeps its original enrolledUnderAcr") {
                    val account = theAccount()
                    account.personId.shouldNotBeNull()
                    account.email.shouldNotBeNull()
                    // Not upgraded afterwards: the level is fixed at enrollment time
                    // (docs/04-orchestrierung.md, "IAL und AAL").
                    account.authenticationMethods.single { it.method == "password" }.enrolledUnderAcr shouldBe "loa1"
                }
            }
        }

        given("an account already registered enroll-first with a real person, and a second, different account") {
            `when`("the second account's closing identification offer resolves to the SAME already-registered person") {
                val firstChannelId = enrolledUpToIdentificationOffer()
                post("/orchestrator/api/v1/channels/$firstChannelId/answer", """{"answer":"accept"}""")
                identifyAsMax(firstChannelId)
                val firstAccountId = accountService.allAccountIds().single()

                // Second account on a fresh device identifies as the same person: a merge, not supported.
                currentBindingKeyRef = "a-completely-different-binding-key"
                val secondChannelId = enrolledUpToIdentificationOffer()
                post("/orchestrator/api/v1/channels/$secondChannelId/answer", """{"answer":"accept"}""")

                val result = runCatching { identifyAsMax(secondChannelId) }

                then("it is rejected as a conflict") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
                then("the second account stays unidentified") {
                    val secondAccountId = (accountService.allAccountIds() - firstAccountId).single()
                    accountService.findAccount(secondAccountId)!!.personId.shouldBeNull()
                }
            }
        }

        given("an account already registered enroll-first and fully set up, on a different device") {
            `when`("confirming the SAME already-confirmed email address, with no other proof of that account") {
                // First device: a credentialed account with no person behind it.
                val firstChannelId = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
                val sharedEmail = confirmEmail(firstChannelId)
                enrollSms(firstChannelId)
                enrollPassword(firstChannelId)
                post("/orchestrator/api/v1/channels/$firstChannelId/answer", """{"answer":"decline"}""")
                val firstAccountId = accountService.allAccountIds().single()

                // A different device that only knows the same email address.
                currentBindingKeyRef = "binding-" + UUID.randomUUID()
                val secondChannelId = post("/orchestrator/api/v1/app/channels", """{"intent":"register"}""").channel()["channelSessionId"] as String
                val confirmToolSessionId = post("/tools/api/confirm-email/v1?channel=$secondChannelId").nextRaw()["toolSessionId"] as String
                val (code, _) = captureMockTan {
                    patch("/tools/api/confirm-email/v1/$confirmToolSessionId", """{"email":"$sharedEmail"}""")
                }

                val result = runCatching { patch("/tools/api/confirm-email/v1/$confirmToolSessionId", """{"code":"$code"}""") }

                then("it is rejected as a conflict, the second device never gets bound to the first account") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                }
                then("the first account is untouched: still the only one, with its own two methods") {
                    accountService.allAccountIds() shouldBe setOf(firstAccountId)
                    accountService.findAccount(firstAccountId)!!.authenticationMethods.map { it.method }.toSet() shouldBe setOf("sms", "password")
                }
            }
        }

        given("a device already durably linked to somebody else's account, registering enroll-first") {
            `when`("the run declines the identification offer and then the closing rebind prompt") {
                // Somebody else's account owns this physical device key.
                val otherAccountId = accountFixtures.seedAccount(bindDeviceKeyRef = currentBindingKeyRef)
                val channelSessionId = enrolledUpToIdentificationOffer()
                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")
                // Only now is the rebind asked. The enrollments above do not take the device on their own.
                val rebindPrompt = get("/orchestrator/api/v1/channels/$channelSessionId")

                val declined = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")

                then("the rebind is asked as a prompt") {
                    rebindPrompt.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
                }
                then("the run finishes, and the other account keeps the device link") {
                    declined.channel()["state"] shouldBe "AUTHENTICATED"
                    linkedAccountId() shouldBe otherAccountId
                }
            }

            `when`("the run declines the identification offer and accepts the closing rebind prompt") {
                val otherAccountId = accountFixtures.seedAccount(bindDeviceKeyRef = currentBindingKeyRef)
                val channelSessionId = enrolledUpToIdentificationOffer()
                post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"decline"}""")

                val accepted = post("/orchestrator/api/v1/channels/$channelSessionId/answer", """{"answer":"accept"}""")

                then("the run finishes") {
                    accepted.channel()["state"] shouldBe "AUTHENTICATED"
                }
                then("the device moves to the newly registered account") {
                    val linked = linkedAccountId()
                    linked shouldNotBe otherAccountId
                    accountService.allAccountIds() shouldContain linked
                }
            }
        }
    }

    /** Which account this test's physical device key currently resolves to, if any. */
    private fun linkedAccountId(): AccountId? =
        jdbcTemplate.queryForList(
            "SELECT account_id FROM orchestrator.device_account_link WHERE binding_key_ref = ?",
            Long::class.java, currentBindingKeyRef
        ).firstOrNull()?.let(::AccountId)
}
