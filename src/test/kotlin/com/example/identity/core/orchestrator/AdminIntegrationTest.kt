package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * The operator endpoints behind the admin login (AdminSecurityConfig): locked without the right
 * credentials, and - once in - the log across all accounts, the account list and deletion. The demo
 * reset is in ActiveSessionsIntegrationTest.
 */
class AdminIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun adminGet(path: String, headers: HttpHeaders = adminHeaders()): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port$path", HttpMethod.GET, HttpEntity<Void>(headers), mapType).body!!

    private fun adminList(path: String): List<Map<String, Any?>> =
        restTemplate.exchange(
            "http://localhost:$port$path", HttpMethod.GET, HttpEntity<Void>(adminHeaders()),
            object : ParameterizedTypeReference<List<Map<String, Any?>>>() {}
        ).body!!

    private fun adminCall(method: HttpMethod, path: String): HttpStatus =
        restTemplate.exchange("http://localhost:$port$path", method, HttpEntity<Void>(adminHeaders()), Void::class.java).statusCode as HttpStatus

    private fun accountIds(): List<Long> = adminList("/orchestrator/admin/accounts").map { (it["accountId"] as Number).toLong() }

    init {
        given("the operator endpoints") {
            `when`("they are called without credentials and with wrong ones") {
                val wrong = HttpHeaders().apply { setBasicAuth("admin", "falsch") }
                val results = listOf(HttpHeaders(), wrong).map { headers ->
                    runCatching { adminGet("/orchestrator/admin/registration-order", headers) }
                }

                then("they answer 401, without a browser login challenge") {
                    results.forEach { result ->
                        val refused = shouldThrow<HttpClientErrorException> { result.getOrThrow() }
                        refused.statusCode shouldBe HttpStatus.UNAUTHORIZED
                        refused.responseHeaders?.getFirst(HttpHeaders.WWW_AUTHENTICATE) shouldBe null
                    }
                }
            }

            `when`("a user name is guessed six times, then the operator logs in") {
                val guess = HttpHeaders().apply { setBasicAuth("intruder", "falsch") }
                val guesses = List(6) { runCatching { adminGet("/orchestrator/admin/registration-order", guess) } }
                val operator = runCatching { adminGet("/orchestrator/admin/registration-order", adminHeaders()) }

                then("five guesses are refused with 401, the sixth is locked out with 429") {
                    guesses.map { shouldThrow<HttpClientErrorException> { it.getOrThrow() }.statusCode } shouldBe
                        List(5) { HttpStatus.UNAUTHORIZED } + HttpStatus.TOO_MANY_REQUESTS
                }
                then("the operator's own login is not locked") {
                    operator.isSuccess shouldBe true
                }
            }
        }

        given("an account created by a registration") {
            `when`("the operator reads the log and the account list") {
                identifyAndConfirmEmail()

                val accounts = adminList("/orchestrator/admin/accounts")
                @Suppress("UNCHECKED_CAST")
                val logAccounts = adminGet("/orchestrator/admin/journey-trace?limit=200")["accounts"] as List<Map<String, Any?>>

                then("the account is listed") {
                    accounts.map { it["displayName"] } shouldContain "Max Muster"
                }
                then("the log across all accounts attributes its journey") {
                    logAccounts.map { it["displayName"] } shouldContain "Max Muster"
                }
            }

            `when`("the operator deletes it") {
                identifyAndConfirmEmail()
                val accountId = (adminList("/orchestrator/admin/accounts").single { it["displayName"] == "Max Muster" }["accountId"] as Number).toLong()

                val deleted = adminCall(HttpMethod.DELETE, "/orchestrator/admin/accounts/$accountId")
                val remaining = accountIds()

                then("it is gone from the list") {
                    deleted shouldBe HttpStatus.NO_CONTENT
                    remaining shouldNotContain accountId
                }
            }
        }

        given("an account that never ran a journey") {
            `when`("the operator reads the log") {
                val accountId = seedRegisteredAccount()

                @Suppress("UNCHECKED_CAST")
                val accounts = adminGet("/orchestrator/admin/journey-trace")["accounts"] as List<Map<String, Any?>>

                then("the log's person filter still offers it") {
                    accounts.map { (it["accountId"] as Number).toLong() } shouldContain accountId.value
                }
            }
        }
    }
}
