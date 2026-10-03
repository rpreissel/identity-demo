package com.example.identity.tools.ident_eid.internal

import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.ident_eid.EID_RESTRICTED_ID
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.TEST_CLOCK
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.tools.ident_eid.internal.EidFixtures.CARD
import com.example.identity.tools.ident_eid.internal.EidFixtures.CARD_FIELDS
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.ClaimSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import java.util.UUID

/** One tool session starting from [initial]; [session] is what the handler last saved. */
private class Fixture(initial: IdentEidToolSession) {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = InMemoryToolSessionData().also { it.save(toolSessionId, initial) }

    /** The session as the handler last saved it. */
    val session: IdentEidToolSession get() = sessions.stored(toolSessionId)
    val handler = IdentEidToolHandler(sessions, clock = TEST_CLOCK)
}

/**
 * Pins the claims a successful ident-eid run asserts: what the card showed, including the address,
 * each under the tool's own trust anchor. No PERSON_ID and no KVNR: a card carries neither, and
 * binding one is `ident-kvnr`'s act (ADR-18). The restricted_id claim becomes the replaceable
 * recognition anchor for a later eid run (ADR-19).
 */
class IdentEidToolHandlerTest : BehaviorSpec({

    given("no ident-eid tool session yet") {
        val f = Fixture(IdentEidToolSession())

        `when`("a tool session starts") {
            val outcome = f.handler.start(ToolSessionId(UUID.randomUUID()))

            then("it names the one step input and asks for the card data only - the PIN is staged, not requested yet") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(CARD_FIELDS))
            }
        }
    }

    given("an ident-eid session with the card read, waiting for the PIN") {
        val f = Fixture(readCard())

        `when`("the correct mock PIN arrives") {
            val identified = f.handler.patch(f.toolSessionId, EidPatchFields(pin = IdentEidFlow.MOCK_PIN))
                .shouldBeInstanceOf<ToolOutcome.Completed.Identified>()

            then("it attests every card attribute as a claim under its own tool anchor") {
                val source = ClaimSource(tool("ident-eid").toolId.value)
                identified.claims shouldBe listOf(
                    Claim(AttributeType.FAMILY_NAME, "Muster", source, tool("ident-eid").maxAcr),
                    Claim(AttributeType.GIVEN_NAMES, "Max", source, tool("ident-eid").maxAcr),
                    Claim(AttributeType.BIRTH_DATE, "1970-01-01", source, tool("ident-eid").maxAcr),
                    Claim(AttributeType.STREET_ADDRESS, "Musterweg 1", source, tool("ident-eid").maxAcr),
                    Claim(AttributeType.POSTAL_CODE, "12345", source, tool("ident-eid").maxAcr),
                    Claim(AttributeType.LOCALITY, "Musterstadt", source, tool("ident-eid").maxAcr),
                    Claim(EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", source, tool("ident-eid").maxAcr)
                )
            }

            then("it resolves nobody - no person reference is asserted") {
                identified.personId.shouldBeNull()
            }

            then("the audit blob carries only what no claim can - provider, tx ids, evidence hash") {
                identified.auditDetails?.get("locality").shouldBeNull()
                // Never the document number (§ 20 PAuswG); it only goes into the evidence hash.
                identified.auditDetails?.get("documentNumber").shouldBeNull()
                identified.auditDetails?.get("evidenceHash").shouldBeInstanceOf<String>().shouldStartWith("sha256:")
            }
        }
    }

    given("another ident-eid session with the card read, waiting for the PIN") {
        val f = Fixture(readCard())

        `when`("a wrong PIN arrives") {
            val outcome = f.handler.patch(f.toolSessionId, EidPatchFields(pin = "000000"))

            then("it fails without naming a person - there is none to throttle against") {
                outcome.shouldBeInstanceOf<ToolOutcome.Failed.Identification>().attemptedPersonId.shouldBeNull()
            }

            then("only the PIN is dropped, the checked card data stays") {
                f.session.pinHash.shouldBeNull()
                f.session.familyName shouldBe "Muster"
                f.session.restrictedId shouldBe "T0103005K1D5S0V8T9W6UM2RTX"
            }
        }
    }

    given("a fresh ident-eid session") {
        val f = Fixture(IdentEidToolSession())

        `when`("complete card data with a malformed postal code arrives") {
            val outcome = f.handler.patch(f.toolSessionId, CARD.copy(postalCode = "1234"))

            then("it fails with the one reason that names no field") {
                outcome shouldBe ToolOutcome.Failed.Identification(Text("Die Kartendaten sind ungültig"), attemptedPersonId = null)
            }

            then("the whole card data is dropped") {
                f.session.familyName.shouldBeNull()
                f.session.postalCode.shouldBeNull()
                f.session.restrictedId.shouldBeNull()
            }
        }

        `when`("the page is read after that rejection") {
            val outcome = f.handler.read(f.toolSessionId)

            then("it asks for all card data again") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(CARD_FIELDS))
            }
        }
    }

    given("another fresh ident-eid session") {
        val f = Fixture(IdentEidToolSession())

        `when`("well-formed card data arrives") {
            val outcome = f.handler.patch(f.toolSessionId, CARD)

            then("it stays in step input and asks for the PIN") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("pin")))
            }
        }
    }
})

private fun readCard() = IdentEidToolSession(
    familyName = CARD.familyName,
    givenNames = CARD.givenNames,
    birthDate = CARD.birthDate,
    streetAddress = CARD.streetAddress,
    postalCode = CARD.postalCode,
    locality = CARD.locality,
    restrictedId = CARD.restrictedId,
)
