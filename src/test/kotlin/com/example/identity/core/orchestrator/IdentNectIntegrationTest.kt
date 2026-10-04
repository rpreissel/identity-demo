package com.example.identity.core.orchestrator

import org.springframework.http.HttpStatus
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.web.client.HttpClientErrorException
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * ident-nect end to end (docs/03-tool-architektur.md, ident-nect): jump URL, identification on
 * Nect's page (here `/mock-nect`), return with the case id, and the backend redeems the result.
 * Like ident-eid it attests and resolves nobody; ident-kvnr binds the person afterwards (ADR-18).
 */
class IdentNectIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }

        data class Started(val channelSessionId: String, val toolSessionId: String, val caseId: String)

        fun start(): Started {
            val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val activated = post("/tools/api/ident-nect/v1?channel=$channelSessionId")
            activated.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-nect", "step" to "redirect")
            val stepData = activated.stepData()
            stepData["kind"] shouldBe "nect-redirect"
            val caseId = stepData["caseId"] as String
            stepData["jumpUrl"] shouldBe "/nect/?case=$caseId"
            return Started(channelSessionId, activated.nextRaw()["toolSessionId"] as String, caseId)
        }

        val max = """"name":"Muster","vorname":"Max","geburtsdatum":"1985-06-15""""
        val maxAddress = """"strasse":"Musterstraße 1","plz":"12345","ort":"Musterstadt""""

        /** What the jump page does when the user finishes there; answers where Nect sends the browser. */
        fun finishAtNect(caseId: String, procedure: String, attributes: String, pin: String? = null, expiryDate: String? = null): String {
            val pinField = pin?.let { ""","pin":"$it"""" } ?: ""
            val expiryField = expiryDate?.let { ""","expiryDate":"$it"""" } ?: ""
            return post("/mock-nect/cases/$caseId/result", """{"procedure":"$procedure","attributes":{$attributes}$pinField$expiryField}""")["redirectUri"] as String
        }

        fun report(toolSessionId: String, caseId: String) =
            patch("/tools/api/ident-nect/v1/$toolSessionId", """{"caseId":"$caseId"}""")

        fun assignKvnr(channelSessionId: String) {
            val kvnrSession = post("/tools/api/ident-kvnr/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
            patch("/tools/api/ident-kvnr/v1/$kvnrSession", """{"kvnr":"A123456789"}""")
        }

        fun evidenceJsonOf(channelSessionId: String): String = jdbcTemplate.queryForObject(
            """
            SELECT ae.methods FROM orchestrator.session_evidence ae
            JOIN orchestrator.channel_session cs ON cs.session_evidence_id = ae.id
            WHERE cs.id = CAST(? AS UUID)
            """,
            String::class.java,
            channelSessionId
        )!!

        fun personAnchorsOf(channelSessionId: String): Int = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM account.anchor an
            JOIN orchestrator.channel_session cs ON cs.account_id = an.account_id
            WHERE cs.id = CAST(? AS UUID) AND an.attribute_type = 'person_id'
            """,
            Int::class.java,
            channelSessionId
        )!!

        given("a registration identifying with the eID via Nect") {
            `when`("the user finishes at Nect, the return reports the case and the KVNR is supplied") {
                val run = start()
                val requested = get("/mock-nect/cases/${run.caseId}")["requested"]
                val redirectUri = finishAtNect(run.caseId, "eid", "$max,$maxAddress,\"restrictedId\":\"NECT-EID-MAX\"", pin = "123456")
                val attested = report(run.toolSessionId, run.caseId)
                assignKvnr(run.channelSessionId)

                then("the case asks Nect for exactly what the registration needs") {
                    requested shouldBe listOf("family_name", "given_names", "birth_date", "address", "eid_pseudonym")
                }
                then("Nect sends the user back to the app with the case id") {
                    redirectUri shouldBe "/app/?nectCaseId=${run.caseId}"
                }
                then("the backend redeems the case as nect-eid and ident-kvnr follows") {
                    attested.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-kvnr", "step" to "input")
                    evidenceJsonOf(run.channelSessionId) shouldContain "nect-eid"
                }
                then("Nect's card pseudonym becomes its own anchor, never the one ident-eid writes (§18 PAuswG)") {
                    jdbcTemplate.queryForList(
                        """
                        SELECT an.attribute_type || '=' || an.normalized_value FROM account.anchor an
                        JOIN orchestrator.channel_session cs ON cs.account_id = an.account_id
                        WHERE cs.id = CAST(? AS UUID) AND an.attribute_type IN ('nect_restricted_id', 'restricted_id')
                        """.trimIndent(),
                        String::class.java,
                        run.channelSessionId
                    ) shouldBe listOf("nect_restricted_id=NECT-EID-MAX")
                }
                then("ident-kvnr binds the register's person") {
                    personAnchorsOf(run.channelSessionId) shouldBe 1
                }
            }
        }

        given("a passport whose chip spells the names its own way") {
            `when`("the passport case is reported and the KVNR is supplied") {
                val run = start()
                finishAtNect(
                    run.caseId, "epass",
                    """"name":"MUSTER","vorname":"MAX","geburtsdatum":"1985-06-15","documentNumber":"C01X00T47","issuingState":"D"""",
                    expiryDate = "2099-01-01"
                )
                report(run.toolSessionId, run.caseId)
                assignKvnr(run.channelSessionId)

                then("ident-kvnr still binds the register person - names compare in MRZ form") {
                    personAnchorsOf(run.channelSessionId) shouldBe 1
                }
            }
        }

        given("a web channel that names Keycloak's action URL as the return address") {
            val actionUrl = "https://kc.test/realms/Demo/login-actions/authenticate?session_code=c1&execution=e1&client_id=web&tab_id=t1"

            `when`("the user finishes at Nect and Keycloak forwards the query") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val activated = post("/tools/api/ident-nect/v1?channel=$channelSessionId", """{"returnUri":"$actionUrl"}""")
                val caseId = activated.stepData()["caseId"] as String
                val toolSessionId = activated.nextRaw()["toolSessionId"] as String
                val redirectUri = finishAtNect(caseId, "eudi", max)
                // Keycloak hands the query on as it is: nectCaseId, not caseId.
                val attested = patch("/tools/api/ident-nect/v1/$toolSessionId", """{"nectCaseId":"$caseId"}""")

                then("Nect sends the user back there") {
                    redirectUri shouldBe "$actionUrl&nectCaseId=$caseId"
                }
                then("the forwarded query reports the case") {
                    attested.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-kvnr", "step" to "input")
                    evidenceJsonOf(channelSessionId) shouldContain "nect-eudi"
                }
            }

            `when`("the case is cancelled at Nect and the user retries") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val activated = post("/tools/api/ident-nect/v1?channel=$channelSessionId", """{"returnUri":"$actionUrl"}""")
                val caseId = activated.stepData()["caseId"] as String
                val toolSessionId = activated.nextRaw()["toolSessionId"] as String
                post("/mock-nect/cases/$caseId/cancellation")
                val failed = patch("/tools/api/ident-nect/v1/$toolSessionId", """{"caseId":"$caseId"}""")
                // A Keycloak form posts strings; "true" must count as the flag.
                val retried = patch("/tools/api/ident-nect/v1/$toolSessionId", """{"retry":"true"}""")
                val newCase = retried.stepData()["caseId"] as String
                val redirectUri = finishAtNect(newCase, "eudi", max)

                then("the report of the cancelled case fails") {
                    failed.stepData()["kind"] shouldBe "failed-attempt"
                }
                then("the retry opens a fresh case that keeps the return address") {
                    newCase shouldNotBe caseId
                    redirectUri shouldBe "$actionUrl&nectCaseId=$newCase"
                }
            }

            `when`("the channel names an address outside the configured prefixes") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val result = runCatching {
                    restTemplate.exchange(
                        "http://localhost:$port/tools/api/ident-nect/v1?channel=$channelSessionId",
                        HttpMethod.POST,
                        HttpEntity("""{"returnUri":"https://attacker.example/return"}""", headers()),
                        String::class.java
                    )
                }

                then("it is refused with 400, before any case exists") {
                    shouldThrow<HttpClientErrorException.BadRequest> { result.getOrThrow() }
                        .responseBodyAsString shouldContain "BAD_REQUEST"
                }
            }
        }

        given("an activation of ident-nect whose body is no JSON") {
            `when`("it is posted") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val broken = runCatching { post("/tools/api/ident-nect/v1?channel=$channelSessionId", "{not json") }
                val after = get("/orchestrator/api/v1/channels/$channelSessionId")

                then("it is a bad request") {
                    shouldThrow<HttpClientErrorException> { broken.getOrThrow() }.statusCode shouldBe HttpStatus.BAD_REQUEST
                }
                then("nothing was activated: the body is read before the tool (ApiBoundaryArchitectureTest)") {
                    after.nextRaw()["toolSessionId"] shouldBe null
                }
            }
        }
    }
}
