package com.example.identity.tools.ident_fsc.internal

import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.directory.ActivationCodes
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.ClaimSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.util.UUID

private val BIRTHDATE = LocalDate.of(1985, 6, 15)
private val PERSON = PartnerNumber("P000000007")

/** The one answer a wrong code and a rate-limited person both get, so the lock reveals nothing. */
private val WRONG_CODE_ANSWER = ToolOutcome.Failed.Identification(Text("Freischaltcode ungueltig oder abgelaufen"), attemptedPersonId = PERSON)

/**
 * One tool session starting from `initial`. The register knows [PERSON] as Max Muster, born
 * [BIRTHDATE], without a member number; no activation code is valid until a test says so.
 */
private class Fixture(initial: IdentFscToolSession = IdentFscToolSession()) {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = InMemoryToolSessionData().also { it.save(toolSessionId, initial) }

    /** The session as the handler last saved it. */
    val session: IdentFscToolSession get() = sessions.stored(toolSessionId)
    val activationCodes = mockk<ActivationCodes>().also {
        every { it.digest(any()) } answers { "digest:" + firstArg<String>() }
        every { it.isValid(any(), any()) } returns false
    }
    val personDirectory = mockk<PersonDirectory>().also {
        every { it.memberNumberOf(any()) } returns null
        every { it.matchesPersonalDetails(any(), any(), any(), any()) } returns false
        every { it.matchesPersonalDetails(PERSON, "Muster", "Max", BIRTHDATE) } returns true
    }
    val handler = IdentFscToolHandler(sessions, activationCodes, personDirectory)

    fun withValidCode(person: PartnerNumber, code: String) = apply {
        every { activationCodes.isValid(person, "digest:$code") } returns true
    }

    fun patch(
        kvnr: String? = null,
        birthDate: LocalDate? = null,
        familyName: String? = null,
        givenNames: String? = null,
        fsc: String? = null,
        personId: PartnerNumber? = null,
        rateLimited: Boolean = false,
    ): ToolOutcome = handler.patch(toolSessionId, kvnr, partnerNumber = null, familyName, givenNames, birthDate, fsc, personId, rateLimited)
}

private fun verifiedPersonalDetails() = IdentFscToolSession(
    kvnr = "A123456789",
    personId = PERSON,
    familyName = "Muster",
    givenNames = "Max",
    birthDate = BIRTHDATE,
)

/**
 * Pins the claims a successful ident-fsc run asserts: FSC is a master-data channel, so every
 * claim carries PERSON_DIRECTORY as its trust anchor, not the tool's own id. Also covers the
 * rejections and the rate limit; [IdentFscFlowTest] covers only the flow.
 */
class IdentFscToolHandlerTest : BehaviorSpec({

    given("no ident-fsc tool session yet") {
        val f = Fixture()

        `when`("a tool session starts") {
            val outcome = f.handler.start(ToolSessionId(UUID.randomUUID()))

            then("it asks for the personal data at step input - the code is staged, not requested yet") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("kvnr", "familyName", "givenNames", "birthDate")))
            }
        }
    }

    given("a fresh session") {
        val f = Fixture()

        `when`("personal data arrives that matches the register, without a code") {
            val outcome = f.patch(kvnr = "A123456789", familyName = "Muster", givenNames = "Max", birthDate = BIRTHDATE, personId = PERSON)

            then("it asks for the code next, still at step input") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("fsc")))
            }
        }
    }

    given("another fresh session") {
        val f = Fixture()

        `when`("personal data arrives whose KVNR resolved no person") {
            val outcome = f.patch(kvnr = "Z999999999", familyName = "Muster", givenNames = "Max", birthDate = BIRTHDATE, personId = null)

            then("it fails with the same answer as a mismatch, naming no person") {
                outcome shouldBe ToolOutcome.Failed.Identification(Text("Die Angaben passen zu keiner Person, die wir kennen"), attemptedPersonId = null)
            }

            then("no code is checked") {
                verify(exactly = 0) { f.activationCodes.isValid(any(), any()) }
            }
        }
    }

    given("verified personal data and a valid code") {
        val f = Fixture(verifiedPersonalDetails()).withValidCode(PERSON, "VALIDCODE")

        `when`("the code is submitted") {
            val outcome = f.patch(fsc = "VALIDCODE")

            then("it identifies, asserting the master-data attributes as claims under PERSON_DIRECTORY") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>().claims shouldBe listOf(
                    Claim(AttributeType.PERSON_ID, "P000000007", ClaimSource.PERSON_DIRECTORY, tool("ident-fsc").maxAcr),
                    Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY, tool("ident-fsc").maxAcr),
                    Claim(AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY, tool("ident-fsc").maxAcr),
                    Claim(AttributeType.GIVEN_NAMES, "Max", ClaimSource.PERSON_DIRECTORY, tool("ident-fsc").maxAcr),
                    Claim(AttributeType.BIRTH_DATE, "1985-06-15", ClaimSource.PERSON_DIRECTORY, tool("ident-fsc").maxAcr)
                )
            }
        }
    }

    given("verified personal data and a code that is not valid") {
        val f = Fixture(verifiedPersonalDetails())

        `when`("the code is submitted") {
            val outcome = f.patch(fsc = "WRONGCODE")

            then("it fails and names the person, so the orchestrator charges that person") {
                outcome shouldBe WRONG_CODE_ANSWER
            }

            then("only the code is dropped, the verified personal data stays") {
                f.session.fscHash shouldBe null
                f.session.kvnr shouldBe "A123456789"
            }
        }
    }

    given("verified personal data of a rate-limited person, and a valid code") {
        val f = Fixture(verifiedPersonalDetails()).withValidCode(PERSON, "VALIDCODE")

        `when`("the code is submitted") {
            val outcome = f.patch(fsc = "VALIDCODE", rateLimited = true)

            then("the answer is the one for a wrong code") {
                outcome shouldBe WRONG_CODE_ANSWER
            }

            then("the register is not asked") {
                verify(exactly = 0) { f.activationCodes.isValid(any(), any()) }
            }
        }
    }

    given("verified personal data") {
        val f = Fixture(verifiedPersonalDetails())

        `when`("a corrected birth date arrives that does not match the register") {
            val outcome = f.patch(birthDate = BIRTHDATE.plusDays(1))

            then("it fails right away and names the person") {
                outcome shouldBe ToolOutcome.Failed.Identification(Text("Die Angaben passen zu keiner Person, die wir kennen"), attemptedPersonId = PERSON)
            }

            then("no code is asked for or checked") {
                verify(exactly = 0) { f.activationCodes.isValid(any(), any()) }
            }

            then("the personal data is dropped") {
                f.session.kvnr shouldBe null
                f.session.birthDate shouldBe null
            }
        }
    }

    given("a Partner, verified by Partnernummer without a KVNR (ADR-34), and a valid code") {
        val f = Fixture(
            IdentFscToolSession(
                partnerNumber = "P000000004", personId = PartnerNumber("P000000004"),
                familyName = "Schulz", givenNames = "Paula", birthDate = BIRTHDATE,
            )
        ).withValidCode(PartnerNumber("P000000004"), "PAULA2026")

        `when`("the code is submitted") {
            val outcome = f.patch(fsc = "PAULA2026")

            then("it identifies, and no KVNR claim is asserted") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>().claims.map { it.attributeType } shouldBe
                    listOf(AttributeType.PERSON_ID, AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)
            }
        }
    }
})
