package com.example.identity.core.orchestrator.keycloak

import com.example.identity.core.orchestrator.IntegrationTestSupport
import com.example.identity.core.orchestrator.support.AccountFixtures
import io.kotest.matchers.shouldBe
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
class KeycloakAccountLookupIntegrationTest : IntegrationTestSupport() {

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
        given("an identified account with a confirmed address and a login method") {
            fun seeded() = accountFixtures.seedAccount(methods = listOf(AccountFixtures.Method.Sms()))

            `when`("Keycloak looks it up by id") {
                val accountId = seeded()
                binding(accountId.toString())

                val byId = lookup("accounts/$accountId")

                then("it gets the account id, the address as email and username, and the names") {
                    byId["accountId"] shouldBe accountId.value.toInt()
                    byId["email"] shouldBe AccountFixtures.EMAIL
                    byId["username"] shouldBe AccountFixtures.EMAIL
                    byId["emailVerified"] shouldBe true
                    byId["firstName"] shouldBe AccountFixtures.VORNAME
                }
                then("the account id is also an attribute") {
                    @Suppress("UNCHECKED_CAST")
                    (byId["attributes"] as Map<String, Any?>)["orchestratorAccountId"] shouldBe accountId.toString()
                }
            }

            `when`("Keycloak searches by email") {
                val accountId = seeded()
                binding("account-lookup")

                val found = lookup("accounts?email=${AccountFixtures.EMAIL}")

                then("it finds the account") {
                    found["accountId"] shouldBe accountId.value.toInt()
                }
            }

            `when`("Keycloak searches by the address as username") {
                val accountId = seeded()
                binding("account-lookup")

                val found = lookup("accounts?username=${AccountFixtures.EMAIL}")

                then("it finds the account") {
                    found["accountId"] shouldBe accountId.value.toInt()
                }
            }

            `when`("Keycloak searches by the username account-<id>") {
                val accountId = seeded()
                binding("account-lookup")

                val found = lookup("accounts?username=account-$accountId")

                then("it finds the account") {
                    found["accountId"] shouldBe accountId.value.toInt()
                }
            }

            `when`("a lookup by id carries the search binding instead of the account's") {
                val accountId = seeded()
                binding("account-lookup")

                val refused = status("accounts/$accountId")

                then("the mismatched binding is refused") {
                    refused shouldBe HttpStatus.UNAUTHORIZED
                }
            }
        }

        given("an account still being set up - identified and confirmed, but no login method yet (ADR-46)") {
            `when`("Keycloak searches by email and looks it up by id") {
                val accountId = accountFixtures.seedAccount()
                binding("account-lookup")
                val byEmail = status("accounts?email=${AccountFixtures.EMAIL}")
                binding(accountId.toString())
                val byId = lookup("accounts/$accountId")

                then("the search by email does not find it, only the orchestrator's own lookup by id") {
                    byEmail shouldBe HttpStatus.NOT_FOUND
                    byId["accountId"] shouldBe accountId.value.toInt()
                }
            }
        }

        given("an account without an address") {
            `when`("Keycloak looks it up by id") {
                val accountId = accountFixtures.seedAccount(email = null)
                binding(accountId.toString())

                val byId = lookup("accounts/$accountId")

                then("its username is account-<id>") {
                    byId["username"] shouldBe "account-$accountId"
                }
            }
        }

        given("no account behind the lookup") {
            `when`("an unknown id is looked up") {
                binding("999999")

                val answer = status("accounts/999999")

                then("it is 404") {
                    answer shouldBe HttpStatus.NOT_FOUND
                }
            }

            // Keycloak asks every federation for every name, also the invitation federation's
            // invitation-<id> (ADR-48): a name that is no address is nobody, not a bad request.
            listOf(
                "an unknown address" to "accounts?email=nobody@example.com",
                "an invitation's username" to "accounts?username=invitation-9f86d081",
                "a value that is no address" to "accounts?email=not-an-address",
            ).forEach { (what, path) ->
                `when`("$what is searched") {
                    binding("account-lookup")

                    val answer = status(path)

                    then("it is 404, not a bad request") {
                        answer shouldBe HttpStatus.NOT_FOUND
                    }
                }
            }

            `when`("the accounts are asked for without a search term") {
                binding("account-lookup")

                val answer = status("accounts")

                then("it is refused - there is no list") {
                    answer shouldBe HttpStatus.BAD_REQUEST
                }
            }
        }
    }
}
