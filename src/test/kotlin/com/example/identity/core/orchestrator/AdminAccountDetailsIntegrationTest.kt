package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldEndWith
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * The operator's account view (`/orchestrator/admin/account-lookups`, `.../accounts/{id}/details`,
 * `.../accounts/{id}/claim-values`): find an account by an identifier, then inspect it.
 */
class AdminAccountDetailsIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun lookup(query: String): List<Map<String, Any?>> =
        restTemplate.exchange(
            "http://localhost:$port/orchestrator/admin/account-lookups", HttpMethod.POST,
            HttpEntity(mapOf("query" to query), adminHeaders()), mapType
        ).body!!["matches"] as List<Map<String, Any?>>

    private fun adminCall(method: HttpMethod, path: String): Map<String, Any?> =
        restTemplate.exchange("http://localhost:$port/orchestrator/admin$path", method, HttpEntity<Void>(adminHeaders()), mapType).body!!

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.list(key: String): List<Map<String, Any?>> = this[key] as List<Map<String, Any?>>

    private fun registeredAccountId(): Any? = lookup("A123456789").single()["accountId"]

    init {
        given("an account registered with its KVNR") {
            `when`("the operator looks it up by KVNR in lower case, by account number, and by an unknown email") {
                registerAndAuthenticate()
                val accountId = registeredAccountId()
                val byKvnr = lookup("a123456789").single()
                val byNumber = lookup("$accountId").single()
                val unknown = lookup("niemand@example.com")

                then("the KVNR finds the account and names its person") {
                    byKvnr["displayName"] shouldBe "Max Muster"
                    byKvnr["matchedBy"] shouldBe "KVNR"
                }
                then("the account number finds it too, saying so") {
                    byNumber["accountId"] shouldBe accountId
                    byNumber["matchedBy"] shouldBe "ACCOUNT_NUMBER"
                }
                then("an email no account holds finds nothing, without an error") {
                    unknown.shouldBeEmpty()
                }
            }

            `when`("the operator types a name") {
                val refused = runCatching { lookup("Max Muster") }

                then("it is refused: names are not searchable") {
                    shouldThrow<HttpClientErrorException> { refused.getOrThrow() }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
            }

            `when`("the operator opens its details") {
                registerAndAuthenticate()
                val accountId = registeredAccountId()
                val details = adminCall(HttpMethod.GET, "/accounts/$accountId/details")
                val familyName = details.list("claims").first { it["attributeType"] == "family_name" }

                then("it names the person and the role, and lists the anchors it is found by") {
                    details["displayName"] shouldBe "Max Muster"
                    details["role"] shouldNotBe "PROSPECT"
                    details.list("anchors").map { it["attributeType"] } shouldContain "person_id"
                }
                then("each claim says where it came from, its value masked") {
                    familyName["readable"] shouldBe true
                    familyName["maskedValue"] as String shouldEndWith "•••"
                    familyName["claimSource"] shouldNotBe null
                    familyName["retraction"] shouldBe null
                }
                then("the methods, the change log and the sign-in log are there") {
                    details.list("methods").shouldNotBeEmpty()
                    details.list("changeLog").map { it["changeType"] } shouldContain "IDENTIFIED"
                    details.list("signIns").shouldNotBeEmpty()
                }
            }

            `when`("the operator asks for the full values") {
                registerAndAuthenticate()
                val accountId = registeredAccountId()
                val details = adminCall(HttpMethod.GET, "/accounts/$accountId/details")
                @Suppress("UNCHECKED_CAST")
                val values = adminCall(HttpMethod.POST, "/accounts/$accountId/claim-values")["values"] as Map<String, String>
                val familyNameId = details.list("claims").first { it["attributeType"] == "family_name" }["id"]

                then("the masked value is shown in full") {
                    values["$familyNameId"] shouldBe "Muster"
                }
            }
        }

        given("an account number nobody holds") {
            `when`("the operator opens its details") {
                val missing = runCatching { adminCall(HttpMethod.GET, "/accounts/999999/details") }

                then("it is not found") {
                    shouldThrow<HttpClientErrorException> { missing.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }
        }
    }
}
