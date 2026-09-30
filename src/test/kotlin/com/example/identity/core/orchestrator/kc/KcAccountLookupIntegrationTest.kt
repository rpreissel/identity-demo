package com.example.identity.core.orchestrator.kc

import com.example.identity.core.orchestrator.IntegrationTestSupport
import com.example.identity.core.orchestrator.support.AccountFixtures
import com.ninjasquad.springmockk.MockkBean
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.Instant
import java.util.UUID

/**
 * Keycloak reads accounts instead of mirroring them - one indexed read by id, email or username, never a
 * list; each lookup names what it looks up in its peer-auth binding.
 */
class KcAccountLookupIntegrationTest : IntegrationTestSupport() {

    @MockkBean
    private lateinit var peerAuthValidator: PeerAuthValidator

    private fun binding(value: String) {
        every { peerAuthValidator.validate(any(), any(), any()) } returns
            PeerAuthAssertion(jti = UUID.randomUUID().toString(), issuedAt = Instant.now(), channelBinding = value, subject = null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun lookup(path: String): Map<String, Any?> =
        restTemplate.exchange(
            "http://localhost:$port/orchestrator/api/v1/kc/$path", HttpMethod.GET,
            HttpEntity<Void>(HttpHeaders().apply { set("Authorization", "Bearer peer-auth") }), mapType
        ).body!!

    private fun status(path: String): HttpStatus =
        try {
            restTemplate.exchange(
                "http://localhost:$port/orchestrator/api/v1/kc/$path", HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders().apply { set("Authorization", "Bearer peer-auth") }), String::class.java
            ).statusCode as HttpStatus
        } catch (e: HttpClientErrorException) {
            e.statusCode as HttpStatus
        }

    init {
        given("an identified account with a confirmed address") {
            then("Keycloak finds it by id, by email and by username - with names, attributes and the account id") {
                val accountId = accountFixtures.seedAccount(methods = listOf(AccountFixtures.Method.Sms()))

                binding(accountId.toString())
                val byId = lookup("accounts/$accountId")
                byId["accountId"] shouldBe accountId.value.toInt()
                byId["email"] shouldBe AccountFixtures.EMAIL
                byId["username"] shouldBe AccountFixtures.EMAIL
                byId["emailVerified"] shouldBe true
                byId["firstName"] shouldNotBe null
                @Suppress("UNCHECKED_CAST")
                (byId["attributes"] as Map<String, Any?>)["orchestratorAccountId"] shouldBe accountId.toString()

                binding("account-lookup")
                lookup("accounts?email=${AccountFixtures.EMAIL}")["accountId"] shouldBe accountId.value.toInt()
                lookup("accounts?username=${AccountFixtures.EMAIL}")["accountId"] shouldBe accountId.value.toInt()
                lookup("accounts?username=account-$accountId")["accountId"] shouldBe accountId.value.toInt()
            }
        }

        given("an account still being set up - identified and confirmed, but no login method yet (ADR-46)") {
            then("the search by email does not find it, only the orchestrator's own lookup by id") {
                val accountId = accountFixtures.seedAccount()

                binding("account-lookup")
                status("accounts?email=${AccountFixtures.EMAIL}") shouldBe HttpStatus.NOT_FOUND
                binding(accountId.toString())
                lookup("accounts/$accountId")["accountId"] shouldBe accountId.value.toInt()
            }
        }

        given("an account without an address") {
            then("its username is account-<id>") {
                val accountId = accountFixtures.seedAccount(email = null)
                binding(accountId.toString())
                lookup("accounts/$accountId")["username"] shouldBe "account-$accountId"
            }
        }

        given("lookups that must not answer") {
            then("unknown accounts are 404, a mismatched binding is refused, and there is no list") {
                binding("999999")
                status("accounts/999999") shouldBe HttpStatus.NOT_FOUND
                binding("account-lookup")
                status("accounts?email=nobody@example.com") shouldBe HttpStatus.NOT_FOUND
                // Keycloak asks every federation for every name, also the invitation federation's
                // invitation-<id> (ADR-48): a name that is no address is nobody, not a bad request.
                status("accounts?username=invitation-9f86d081") shouldBe HttpStatus.NOT_FOUND
                status("accounts?email=not-an-address") shouldBe HttpStatus.NOT_FOUND
                status("accounts") shouldBe HttpStatus.BAD_REQUEST

                val accountId = accountFixtures.seedAccount()
                binding("account-lookup")
                (status("accounts/$accountId").value() in 400..499) shouldBe true
            }
        }
    }
}
