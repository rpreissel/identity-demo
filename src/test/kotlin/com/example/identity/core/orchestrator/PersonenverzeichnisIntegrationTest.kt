package com.example.identity.core.orchestrator

import com.example.identity.contract.texts.templateOf
import com.example.identity.contract.tool_api.values.PartnerNumber
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * The register's own UI (`/mock-personenverzeichnis`, ADR-31) drives what ident-fsc accepts: an
 * issued code works at once, a revoked one stops working, a changed name does not match. Our side
 * is not told the register changed.
 */
class PersonenverzeichnisIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    private fun registerCall(method: HttpMethod, path: String, body: String? = null): Map<String, Any?> {
        val headers = HttpHeaders().apply { set("Content-Type", "application/json") }
        return restTemplate.exchange("http://localhost:$port/mock-personenverzeichnis$path", method, HttpEntity(body, headers), mapType).body.orEmpty()
    }

    private fun randomVersnr(): String = (10_000_000 + Random.nextInt(89_999_999)).toString()

    /**
     * A person of its own per scenario, so no other suite's seed code or rate limit interferes -
     * insured with us, since only an insured person has a KVNR (ADR-34).
     */
    private fun newPerson(): Triple<PartnerNumber, String, String> {
        val kvnr = "Z" + (1..9).joinToString("") { Random.nextInt(10).toString() }
        val versnr = randomVersnr()
        val created = registerCall(
            HttpMethod.POST, "/personen",
            """{"kvnr":"$kvnr","versnr":"$versnr","name":"Register","vorname":"Rita","geburtsdatum":"1970-01-01"}"""
        )
        return Triple(PartnerNumber(created["id"] as String), kvnr, versnr)
    }

    private fun issue(personId: PartnerNumber): Map<String, Any?> =
        registerCall(HttpMethod.POST, "/personen/$personId/freischaltcodes", """{"gueltigBis":"2099-01-01T00:00:00Z"}""")

    private fun identifyWith(kvnr: String, name: String, code: String): Map<String, Any?> {
        val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        val toolSessionId = post("/tools/api/ident-fsc/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
        return patch(
            "/tools/api/ident-fsc/v1/$toolSessionId",
            """{"kvnr":"$kvnr","familyName":"$name","givenNames":"Rita","birthDate":"1970-01-01","fsc":"$code"}"""
        )
    }

    private fun stepError(response: Map<String, Any?>): Any? = (response["stepData"] as? Map<*, *>)?.get("error")

    private fun demoPersons(): List<Map<*, *>> =
        ((post("/orchestrator/api/v1/app/channels")["demo"] as Map<*, *>)["persons"] as List<*>).map { it as Map<*, *> }

    private fun accountIdOfPerson(personId: String): Long = jdbcTemplate.queryForObject(
        "SELECT account_id FROM account.anchor WHERE attribute_type = 'person_id' AND normalized_value = ?",
        Long::class.java, personId
    )!!

    init {
        given("a person the register has just created") {
            `when`("a freshly issued code is used, then revoked and used again") {
                val (personId, kvnr) = newPerson()
                val brief = issue(personId)
                val code = brief["code"] as String

                val beforeRevocation = identifyWith(kvnr, "Register", code)
                registerCall(HttpMethod.DELETE, "/freischaltcodes/${(brief["freischaltcodeId"] as Number).toLong()}")
                val afterRevocation = identifyWith(kvnr, "Register", code)

                then("the code identifies at once") {
                    stepError(beforeRevocation).shouldBeNull()
                }
                then("it stops doing so once revoked") {
                    templateOf(stepError(afterRevocation)) shouldBe "Freischaltcode ungueltig oder abgelaufen"
                }
            }

            listOf(
                "name" to """"name":"Umbenannt","vorname":"Rita","geburtsdatum":"1970-01-01"""",
                "birthdate" to """"name":"Register","vorname":"Rita","geburtsdatum":"1971-02-02"""",
            ).forEach { (field, changed) ->
                `when`("the register changes the $field after issuing a code") {
                    val (personId, kvnr, versnr) = newPerson()
                    val code = issue(personId)["code"] as String
                    registerCall(HttpMethod.PUT, "/personen/$personId", """{"kvnr":"$kvnr","versnr":"$versnr",$changed}""")

                    val response = identifyWith(kvnr, "Register", code)

                    then("the old personal data no longer matches") {
                        templateOf(stepError(response)) shouldBe "Die Angaben passen zu keiner Person, die wir kennen"
                    }
                }
            }

            `when`("a code is issued and a channel is opened") {
                val (personId, kvnr) = newPerson()
                val code = issue(personId)["code"] as String

                val persona = demoPersons().single { it["kvnr"] == kvnr }

                then("the demo persona picker offers the person with the code from its newest valid letter") {
                    persona["fscCode"] shouldBe code
                    persona["familyName"] shouldBe "Register"
                    persona["email"].shouldBeNull()
                    persona["phoneNumber"].shouldBeNull()
                    persona["restrictedId"].shouldBeNull()
                }
            }

            `when`("the register records an e-mail address and a mobile number and a channel is opened") {
                val (personId, kvnr, versnr) = newPerson()
                val saved = registerCall(
                    HttpMethod.PUT, "/personen/$personId",
                    """{"kvnr":"$kvnr","versnr":"$versnr","name":"Register","vorname":"Rita","geburtsdatum":"1970-01-01",""" +
                        """"email":"rita@example.org","mobilnummer":"+49 170 0000042"}"""
                )

                val persona = demoPersons().single { it["personId"] == personId.value }

                then("the register keeps the mobile number") {
                    saved["mobilnummer"] shouldBe "+49 170 0000042"
                }
                then("the picker offers both") {
                    persona["email"] shouldBe "rita@example.org"
                    persona["phoneNumber"] shouldBe "+49 170 0000042"
                }
            }
        }

        given("a bound person whose KVNR and Versicherungsnummer change in the Personenverzeichnis") {
            `when`("the register changes both numbers, then replaces the Versicherungsnummer, then drops both") {
                val (personId, kvnr) = newPerson()
                val code = issue(personId)["code"] as String
                identifyWith(kvnr, "Register", code)
                val accountId = accountIdOfPerson(personId.value)

                fun anchor(type: String): String? = jdbcTemplate.queryForList(
                    "SELECT normalized_value FROM account.anchor WHERE account_id = ? AND attribute_type = ?",
                    String::class.java, accountId, type
                ).singleOrNull()

                // The account follows via PersonChanged, asynchronously.
                fun eventually(check: () -> Boolean) =
                    await().alias("account follows the Personenverzeichnis").atMost(10, TimeUnit.SECONDS).until(check)

                val versnr = randomVersnr()
                val newKvnr = "Y" + (1..9).joinToString("") { Random.nextInt(10).toString() }
                registerCall(
                    HttpMethod.PUT, "/personen/$personId",
                    """{"kvnr":"$newKvnr","versnr":"$versnr","name":"Register","vorname":"Rita","geburtsdatum":"1970-01-01"}"""
                )
                eventually { anchor("member_number") == versnr }
                val kvnrClaims = jdbcTemplate.queryForList(
                    """
                    SELECT c.normalized_value FROM account.claim c
                    WHERE c.account_id = ? AND c.attribute_type = 'kvnr' AND c.claim_source = 'person_directory'
                    AND NOT EXISTS (SELECT 1 FROM account.retraction r WHERE r.account_id = c.account_id
                        AND r.attribute_type = c.attribute_type AND r.normalized_value = c.normalized_value)
                    """.trimIndent(),
                    String::class.java, accountId
                ).map { it!!.uppercase() }

                val replaced = randomVersnr()
                registerCall(HttpMethod.PUT, "/personen/$personId", """{"kvnr":"$newKvnr","versnr":"$replaced","name":"Register","vorname":"Rita","geburtsdatum":"1970-01-01"}""")
                eventually { anchor("member_number") == replaced }

                // Not insured with us any more: both numbers go, the person stays as a Partner.
                registerCall(HttpMethod.PUT, "/personen/$personId", """{"kvnr":"","versnr":"","name":"Register","vorname":"Rita","geburtsdatum":"1970-01-01"}""")
                eventually { anchor("member_number") == null }

                then("the new KVNR is the account's only current KVNR claim (ADR-34)") {
                    kvnrClaims shouldBe listOf(newKvnr)
                }
                then("the account stays bound to the person as a Partner") {
                    anchor("person_id") shouldBe personId.value
                }
            }
        }

        given("a Partner - no KVNR, no Versicherungsnummer, only the Partnernummer (ADR-34)") {
            `when`("a letter to the Partnernummer is used with ident-fsc") {
                val created = registerCall(HttpMethod.POST, "/personen", """{"name":"Partner","vorname":"Paul","geburtsdatum":"1960-06-06"}""")
                val personId = created["id"] as String
                val code = issue(PartnerNumber(personId))["code"] as String

                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val toolSessionId = post("/tools/api/ident-fsc/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
                val response = patch(
                    "/tools/api/ident-fsc/v1/$toolSessionId",
                    """{"partnerNumber":"${personId.lowercase()}","familyName":"Partner","givenNames":"Paul","birthDate":"1960-06-06","fsc":"$code"}"""
                )

                then("it identifies") {
                    stepError(response).shouldBeNull()
                }
                then("the account is bound without a KVNR") {
                    jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM account.claim WHERE account_id = ? AND attribute_type = 'kvnr'",
                        Int::class.java, accountIdOfPerson(personId)
                    ) shouldBe 0
                }
            }
        }
    }
}
