package com.example.identity.tools.ident_kvnr.internal

import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ToolOutcome
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

private val KVNR_NOT_ASSIGNABLE = Text("Versichertennummer konnte nicht zugeordnet werden")
private val PARTNER_NUMBER_NOT_ASSIGNABLE = Text("Partnernummer konnte nicht zugeordnet werden")

/** A fresh tool session; the register knows no member number until a test adds one. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = InMemoryToolSessionData().also { it.save(toolSessionId, IdentKvnrToolSession()) }

    /** The session as the handler last saved it. */
    val session: IdentKvnrToolSession get() = sessions.stored(toolSessionId)
    val personDirectory = mockk<PersonDirectory>().also {
        every { it.memberNumberOf(any()) } returns null
    }
    val handler = IdentKvnrToolHandler(sessions, personDirectory)

    fun withMemberNumber(person: PartnerNumber, memberNumber: String) = apply {
        every { personDirectory.memberNumberOf(person) } returns memberNumber
    }
}

/**
 * Pins what the correlation step asserts (ADR-18): the register's person reference and KVNR, both
 * under `PERSON_DIRECTORY`, unlike `ident-eid`, which vouches for the card itself. The identity match
 * lives in the account module ([com.example.identity.core.account.application.IdentityMatchingServiceTest]);
 * this tool never sees accounts.
 */
class IdentKvnrToolHandlerTest : BehaviorSpec({

    given("no ident-kvnr tool session yet") {
        val f = Fixture()

        `when`("a tool session starts") {
            val outcome = f.handler.start(ToolSessionId(UUID.randomUUID()))

            then("it asks for the KVNR at step input") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("kvnr")))
            }
        }
    }

    given("an active session and a KVNR the register resolves to the attested person") {
        val f = Fixture()

        `when`("the KVNR arrives") {
            val outcome = f.handler.patch(f.toolSessionId, "A123456789", partnerNumber = null, personId = PartnerNumber("P000000042"), matchesAttestedIdentity = true)

            then("it asserts the person reference and the number, both vouched for by the register") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>().claims shouldBe listOf(
                    Claim(AttributeType.PERSON_ID, "P000000042", ClaimSource.PERSON_DIRECTORY, tool("ident-kvnr").maxAcr),
                    Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY, tool("ident-kvnr").maxAcr)
                )
            }

            then("it keeps the KVNR in the tool session") {
                f.session.kvnr shouldBe "A123456789"
                f.session.partnerNumber shouldBe null
            }
        }
    }

    given("an active session and a KVNR of a person insured with us") {
        val f = Fixture().withMemberNumber(PartnerNumber("P000000042"), "10000001")

        `when`("the KVNR arrives") {
            val outcome = f.handler.patch(f.toolSessionId, "A123456789", partnerNumber = null, personId = PartnerNumber("P000000042"), matchesAttestedIdentity = true)

            then("the Versicherungsnummer comes along as an anchor claim (ADR-34)") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>().claims.last() shouldBe
                    Claim(AttributeType.MEMBER_NUMBER, "10000001", ClaimSource.PERSON_DIRECTORY, tool("ident-kvnr").maxAcr)
            }
        }
    }

    given("an active session and a Partner without a KVNR (ADR-34)") {
        val f = Fixture()

        `when`("the Partnernummer arrives") {
            val outcome = f.handler.patch(f.toolSessionId, kvnr = null, partnerNumber = "P000000004", personId = PartnerNumber("P000000004"), matchesAttestedIdentity = true)

            then("it asserts the person reference only - no KVNR claim") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>().claims shouldBe listOf(
                    Claim(AttributeType.PERSON_ID, "P000000004", ClaimSource.PERSON_DIRECTORY, tool("ident-kvnr").maxAcr)
                )
            }

            then("it keeps the Partnernummer in the tool session") {
                f.session.partnerNumber shouldBe "P000000004"
                f.session.kvnr shouldBe null
            }
        }
    }

    given("an active session and a Partnernummer the register does not know") {
        val f = Fixture()

        `when`("the Partnernummer arrives") {
            val outcome = f.handler.patch(f.toolSessionId, kvnr = null, partnerNumber = "P999999999", personId = null, matchesAttestedIdentity = false)

            then("it fails without saying whether it exists") {
                outcome shouldBe ToolOutcome.Failed.Identification(PARTNER_NUMBER_NOT_ASSIGNABLE, attemptedPersonId = null)
            }
        }
    }

    given("an active session and a KVNR the register does not know") {
        val f = Fixture()

        `when`("the KVNR arrives") {
            val outcome = f.handler.patch(f.toolSessionId, "X999999999", partnerNumber = null, personId = null, matchesAttestedIdentity = false)

            then("it fails with a message that does not reveal whether the number exists") {
                outcome shouldBe ToolOutcome.Failed.Identification(KVNR_NOT_ASSIGNABLE, attemptedPersonId = null)
            }
        }
    }

    given("an active session and a KVNR that belongs to a person other than the one this account had attested") {
        val f = Fixture()

        `when`("the KVNR arrives") {
            val outcome = f.handler.patch(f.toolSessionId, "A123456789", partnerNumber = null, personId = PartnerNumber("P000000042"), matchesAttestedIdentity = false)

            then("it fails exactly like an unknown one, but counts the guess against that person") {
                outcome shouldBe ToolOutcome.Failed.Identification(KVNR_NOT_ASSIGNABLE, attemptedPersonId = PartnerNumber("P000000042"))
            }
        }
    }

    given("an active session") {
        val f = Fixture()

        `when`("neither a KVNR nor a Partnernummer arrives") {
            val outcome = f.handler.patch(f.toolSessionId, kvnr = null, partnerNumber = null, personId = null, matchesAttestedIdentity = false)

            then("it keeps asking for the KVNR") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("kvnr")))
            }
        }
    }
})
