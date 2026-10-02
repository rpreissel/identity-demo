package com.example.identity.core.orchestrator

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.support.AccountFixtures
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain as shouldContainText
import io.kotest.matchers.string.shouldNotContain as shouldNotContainText
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * The two acts of an eID run (docs/12-entscheidungen.md ADR-18): `ident-eid` attests the card, then
 * `ident-kvnr` is offered directly to bind the register's person. Abandoning that step is a valid
 * outcome: the account stays a prospect (ADR-10) with an attested identity.
 */
class IdentEidAssignmentIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }

        /** Card read plus PIN - the whole tool, with nothing typed to look anybody up first. */
        fun attestViaEid(channelSessionId: String): Map<String, Any?> {
            val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-eid")
                .nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$toolSessionId/ident-eid",
                """{"familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","streetAddress":"Musterstraße 1","postalCode":"12345","locality":"Musterstadt","restrictedId":"T0103005K1D5S0V8T9W6UM2RTX"}"""
            )
            return patch("/orchestrator/api/v1/tools/$toolSessionId/ident-eid", """{"pin":"123456"}""")
        }

        /** The second demo person's card - used where a test needs a persona Max's fixtures don't already own. */
        fun attestAsErika(channelSessionId: String): Map<String, Any?> {
            val toolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-eid")
                .nextRaw()["toolSessionId"] as String
            patch(
                "/orchestrator/api/v1/tools/$toolSessionId/ident-eid",
                """{"familyName":"Beispiel","givenNames":"Erika","birthDate":"1990-11-02","streetAddress":"Beispielweg 42","postalCode":"54321","locality":"Beispielhausen","restrictedId":"T0208011X7Y2Q4M6B3LT0T28WJ"}"""
            )
            return patch("/orchestrator/api/v1/tools/$toolSessionId/ident-eid", """{"pin":"123456"}""")
        }

        /** Activates the correlation step the attestation left `next` pointing at. */
        fun activateAssignment(channelSessionId: String): String =
            post("/orchestrator/api/v1/channels/$channelSessionId/tools/ident-kvnr").nextRaw()["toolSessionId"] as String

        /** Runs confirm-email to completion for one specific address on an already-running journey. */
        fun confirmAddress(channelSessionId: String, email: String): Map<String, Any?> {
            val confirmSession = post("/orchestrator/api/v1/channels/$channelSessionId/tools/confirm-email")
                .nextRaw()["toolSessionId"] as String
            val (code, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$confirmSession/confirm-email", """{"email":"$email"}""")
            }
            return patch("/orchestrator/api/v1/tools/$confirmSession/confirm-email", """{"code":"$code"}""")
        }

        /** How many PERSON_ID anchors the channel's account has - 0 for a prospect, 1 once bound. */
        fun personAnchorsOf(channelSessionId: String): Int = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM account.anchor an
            JOIN orchestrator.channel_session cs ON cs.account_id = an.account_id
            WHERE cs.id = CAST(? AS UUID) AND an.attribute_type = 'person_id'
            """,
            Int::class.java,
            channelSessionId
        )!!

        /** The raw `methods` JSON of this channel, read from the row so no endpoint shape matters. */
        fun evidenceJsonOf(channelSessionId: String): String = jdbcTemplate.queryForObject(
            """
            SELECT ae.methods FROM orchestrator.session_evidence ae
            JOIN orchestrator.channel_session cs ON cs.session_evidence_id = ae.id
            WHERE cs.id = CAST(? AS UUID)
            """,
            String::class.java,
            channelSessionId
        )!!

        /** The account the channel currently points at - null once it points at none. */
        fun accountIdOf(channelSessionId: String): AccountId? = jdbcTemplate.queryForObject(
            "SELECT account_id FROM orchestrator.channel_session WHERE id = CAST(? AS UUID)",
            Long::class.java,
            channelSessionId
        )?.let(::AccountId)

        /** How many restricted_id anchors this account holds - the eid attestation's own anchor (ADR-19). */
        fun restrictedIdAnchorsOf(accountId: AccountId): Int = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account.anchor WHERE account_id = ? AND attribute_type = 'restricted_id'",
            Int::class.java,
            accountId.value
        )!!

        fun accountExists(accountId: AccountId): Boolean = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM account.account WHERE id = ?", Int::class.java, accountId.value
        ) == 1

        /** The login choice an existing account with sms and password gets: prove a method, not enroll one. */
        val loginChoice = mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")

        // ADR-19: once an account holds a card's restricted_id, the next run with that card
        // resolves onto it instead of registering a second account.
        given("a card whose restricted_id an existing account already holds") {
            `when`("the card is attested on a fresh channel") {
                val existing = accountFixtures.seedAccount(
                    kvnr = "B987654321", name = "Beispiel", vorname = "Erika",
                    email = "erika.beispiel@example.com",
                    methods = listOf(AccountFixtures.Method.Sms(), AccountFixtures.Method.Password()),
                    restrictedId = "T0208011X7Y2Q4M6B3LT0T28WJ"
                )
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String

                val attested = attestAsErika(channelSessionId)

                then("the run is recognized onto that account instead of registering a new one") {
                    accountIdOf(channelSessionId) shouldBe existing
                }
                then("her account is complete, so the run offers her own methods, not the KVNR step") {
                    attested.next() shouldBe loginChoice
                    @Suppress("UNCHECKED_CAST")
                    (attested.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
            }
        }

        // ADR-20 through the email anchor: a confirmed address resolves accounts, so the
        // disposable account goes into the one that holds it. Skipping the KVNR step is no dead end.
        given("an Interessent whose address belongs to their own account") {
            `when`("the Interessent skips the KVNR step and confirms that address") {
                val existing = accountFixtures.seedAccount(
                    kvnr = "B987654321", name = "Beispiel", vorname = "Erika",
                    email = "erika.beispiel@example.com",
                    methods = listOf(AccountFixtures.Method.Sms())
                )
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                attestAsErika(channelSessionId)
                val disposable = checkNotNull(accountIdOf(channelSessionId)) { "the attestation created no account" }
                delete("/orchestrator/api/v1/tools/${activateAssignment(channelSessionId)}/ident-kvnr")

                confirmAddress(channelSessionId, "erika.beispiel@example.com")

                then("the run continues on the existing account and the disposable one is gone") {
                    accountIdOf(channelSessionId) shouldBe existing
                    accountExists(disposable) shouldBe false
                }
                then("the attestation came along - her card now recognizes this account (ADR-19)") {
                    restrictedIdAnchorsOf(existing) shouldBe 1
                }
            }
        }

        given("an Interessent whose address belongs to somebody else") {
            `when`("the Interessent skips the KVNR step and confirms that address") {
                accountFixtures.seedAccount(
                    kvnr = "B987654321", name = "Beispiel", vorname = "Erika",
                    email = "erika.beispiel@example.com",
                    methods = listOf(AccountFixtures.Method.Sms())
                )
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                // Max's card, Erika's address.
                attestViaEid(channelSessionId)
                val disposable = checkNotNull(accountIdOf(channelSessionId)) { "the attestation created no account" }
                delete("/orchestrator/api/v1/tools/${activateAssignment(channelSessionId)}/ident-kvnr")

                val result = runCatching { confirmAddress(channelSessionId, "erika.beispiel@example.com") }

                then("it is refused - holding a mailbox does not make you that person") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                    accountIdOf(channelSessionId) shouldBe disposable
                }
            }
        }

        given("an eID attestation whose KVNR belongs to an account that already exists") {
            `when`("the KVNR is supplied to the correlation step") {
                val existing = accountFixtures.seedAccount(
                    kvnr = "A123456789", name = "Muster", vorname = "Max",
                    methods = listOf(AccountFixtures.Method.Sms(), AccountFixtures.Method.Password())
                )
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                attestViaEid(channelSessionId)
                val disposable = checkNotNull(accountIdOf(channelSessionId)) { "the attestation created no account" }

                val assigned = patch("/orchestrator/api/v1/tools/${activateAssignment(channelSessionId)}/ident-kvnr", """{"kvnr":"A123456789"}""")

                then("the attestation had created a disposable account of its own") {
                    disposable shouldNotBe existing
                }
                then("the run moved over, and the placeholder is gone rather than left as a stray") {
                    accountIdOf(channelSessionId) shouldBe existing
                    accountExists(disposable) shouldBe false
                }
                then("the attestation came along, audited on the existing account") {
                    restrictedIdAnchorsOf(existing) shouldBe 1
                    jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM account.change_log WHERE account_id = ? AND change_type = 'IDENTIFIED' AND subject = 'eid'",
                        Int::class.java, existing.value
                    ) shouldBe 1
                }
                then("like every route to an existing account, it asks to prove one of its methods") {
                    assigned.next() shouldBe loginChoice
                    @Suppress("UNCHECKED_CAST")
                    (assigned.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
            }
        }

        given("an eID attestation that resolved nobody") {
            `when`("the assignment step is abandoned - the 'jetzt nicht' of this flow") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val attested = attestViaEid(channelSessionId)
                val skipped = delete("/orchestrator/api/v1/tools/${activateAssignment(channelSessionId)}/ident-kvnr")

                then("the attestation points straight at the correlation tool, no prompt in between") {
                    attested.next() shouldBe mapOf("type" to "tool", "toolId" to "ident-kvnr", "step" to "input")
                }
                then("the registration continues where it always does - the address step") {
                    skipped.next() shouldBe mapOf("type" to "tool", "toolId" to "confirm-email", "step" to "input")
                }
                then("the account stays an Interessent") {
                    personAnchorsOf(channelSessionId) shouldBe 0
                }
            }

            `when`("a matching KVNR is supplied") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                attestViaEid(channelSessionId)
                patch("/orchestrator/api/v1/tools/${activateAssignment(channelSessionId)}/ident-kvnr", """{"kvnr":"A123456789"}""")

                then("it binds the register's person to the very same account") {
                    personAnchorsOf(channelSessionId) shouldBe 1
                }
                then("both acts are audited with their role") {
                    // Without it, a `kvnr / loa2` row would read like a procedure that reached loa2 by itself.
                    jdbcTemplate.queryForList(
                        """
                        SELECT e.subject FROM account.change_log e
                        JOIN orchestrator.channel_session cs ON cs.account_id = e.account_id
                        WHERE cs.id = CAST(? AS UUID) AND e.change_type = 'IDENTIFIED' ORDER BY e.occurred_at
                        """,
                        String::class.java,
                        channelSessionId
                    ) shouldBe listOf("eid", "kvnr")
                    jdbcTemplate.queryForObject(
                        """
                        SELECT CAST(e.details AS VARCHAR) FROM account.change_log e
                        JOIN orchestrator.channel_session cs ON cs.account_id = e.account_id
                        WHERE cs.id = CAST(? AS UUID) AND e.change_type = 'IDENTIFIED' AND e.subject = 'kvnr'
                        """,
                        String::class.java,
                        channelSessionId
                    )!! shouldContainText "CORRELATION"
                }
                then("the correlation step leaves no session evidence - it proves nothing, so it prices nothing") {
                    // Recorded, `kvnr` would buy loa2 by typing a semi-public number
                    // (ToolDescriptor.evidenceAxis).
                    val evidence = evidenceJsonOf(channelSessionId)
                    evidence shouldContainText "eid"
                    evidence shouldNotContainText "kvnr"
                }
            }

            `when`("a person is bound and the correlation step is requested a second time") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                attestViaEid(channelSessionId)
                patch("/orchestrator/api/v1/tools/${activateAssignment(channelSessionId)}/ident-kvnr", """{"kvnr":"A123456789"}""")

                val result = runCatching { activateAssignment(channelSessionId) }

                then("it is not offered - a second person would change identity without proof") {
                    // This asserts the offer layer, which is as far as a client can get.
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.CONFLICT
                    personAnchorsOf(channelSessionId) shouldBe 1
                }
            }

            `when`("somebody else's KVNR is supplied on one channel and an unknown one on another") {
                val channelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                attestViaEid(channelSessionId)
                val foreign = patch(
                    "/orchestrator/api/v1/tools/${activateAssignment(channelSessionId)}/ident-kvnr",
                    """{"kvnr":"B987654321"}"""
                )
                val otherChannel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                attestViaEid(otherChannel)
                val unknown = patch(
                    "/orchestrator/api/v1/tools/${activateAssignment(otherChannel)}/ident-kvnr",
                    """{"kvnr":"X999999999"}"""
                )

                then("the foreign KVNR binds nobody") {
                    personAnchorsOf(channelSessionId) shouldBe 0
                }
                then("it is refused exactly like the unknown one - the answer reveals nothing") {
                    foreign.next() shouldBe unknown.next()
                    foreign["stepData"] shouldBe unknown["stepData"]
                    foreign.channel()["state"] shouldBe unknown.channel()["state"]
                }
            }
        }
    }
}
