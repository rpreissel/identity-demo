package com.example.identity.tools.ident_fsc.internal

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.directory.ActivationCodes
import com.example.identity.tools.ident_fsc.IdentFscDescriptor
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.ClaimSource
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Pins the claims a successful ident-fsc run asserts: FSC is a master-data channel, so every
 * claim carries PERSON_DIRECTORY as its trust anchor, not the tool's own id. This also covers the
 * handler's `Completed.Identified` wiring; [IdentFscFlowTest] covers only the flow.
 */
class IdentFscToolHandlerTest : BehaviorSpec({

    val toolSessionId = UUID.randomUUID()
    val repository = mockk<IdentFscToolSessionRepository>()
    val activationCodes = mockk<ActivationCodes> { every { digest(any()) } answers { "digest:" + firstArg<String>() } }
    val personDirectory = mockk<PersonDirectory>()
    val handler = IdentFscToolHandler(IdentFscDescriptor, repository, activationCodes, personDirectory, clock = TEST_CLOCK)

    val birthdate = LocalDate.of(1985, 6, 15)

    fun sessionWithVerifiedPersonalien() = IdentFscToolSession(
        toolSessionId = toolSessionId,
        kvnr = "A123456789",
        personId = "P000000007",
        familyName = "Muster",
        givenNames = "Max",
        birthDate = birthdate,
        createdAt = TEST_NOW
    ).also { data ->
        every { repository.findById(toolSessionId) } returns Optional.of(data)
        every { repository.save(any()) } returns data
        every { personDirectory.memberNumberOf(any()) } returns null
    }

    given("verified personal data and a valid code") {
        sessionWithVerifiedPersonalien()
        every { activationCodes.isValid("P000000007", any()) } returns true

        `when`("the code is submitted") {
            then("it identifies, asserting the master-data attributes as claims under PERSON_DIRECTORY") {
                val outcome = handler.patch(toolSessionId, kvnr = null, partnerNumber = null, familyName = null, givenNames = null, birthDate = null, fsc = "VALIDCODE", personId = null, rateLimited = false)

                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                outcome.claims shouldBe listOf(
                    Claim(AttributeType.PERSON_ID, "P000000007", ClaimSource.PERSON_DIRECTORY, IdentFscDescriptor.maxAcr),
                    Claim(AttributeType.KVNR, "A123456789", ClaimSource.PERSON_DIRECTORY, IdentFscDescriptor.maxAcr),
                    Claim(AttributeType.FAMILY_NAME, "Muster", ClaimSource.PERSON_DIRECTORY, IdentFscDescriptor.maxAcr),
                    Claim(AttributeType.GIVEN_NAMES, "Max", ClaimSource.PERSON_DIRECTORY, IdentFscDescriptor.maxAcr),
                    Claim(AttributeType.BIRTH_DATE, "1985-06-15", ClaimSource.PERSON_DIRECTORY, IdentFscDescriptor.maxAcr)
                )
            }
        }
    }

    given("personal data that does not match the register") {
        `when`("it is submitted") {
            then("it fails right away - no code asked for, none checked - and the data is dropped") {
                clearMocks(activationCodes, answers = false)
                val data = sessionWithVerifiedPersonalien()
                every { personDirectory.matchesPersonalDetails("P000000007", "Muster", "Max", birthdate.plusDays(1)) } returns false

                val outcome = handler.patch(toolSessionId, kvnr = null, partnerNumber = null, familyName = null, givenNames = null, birthDate = birthdate.plusDays(1), fsc = null, personId = null, rateLimited = false)

                outcome shouldBe ToolOutcome.Failed.Identification(Text("Die Angaben passen zu keiner Person, die wir kennen"), attemptedPersonId = "P000000007")
                verify(exactly = 0) { activationCodes.isValid(any(), any()) }
                data.kvnr shouldBe null
                data.birthDate shouldBe null
            }
        }
    }

    given("a Partner - verified by Partnernummer, no KVNR (ADR-34)") {
        then("the code identifies, and no KVNR claim is asserted") {
            val data = IdentFscToolSession(
                toolSessionId = toolSessionId, partnerNumber = "P000000004", personId = "P000000004",
                familyName = "Schulz", givenNames = "Paula", birthDate = birthdate,
                createdAt = TEST_NOW
            )
            every { repository.findById(toolSessionId) } returns Optional.of(data)
            every { repository.save(any()) } returns data
            every { personDirectory.memberNumberOf(any()) } returns null
            every { activationCodes.isValid("P000000004", any()) } returns true

            val outcome = handler.patch(toolSessionId, kvnr = null, partnerNumber = null, familyName = null, givenNames = null, birthDate = null, fsc = "PAULA2026", personId = null, rateLimited = false)

            outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
            outcome.claims.map { it.attributeType } shouldBe
                listOf(AttributeType.PERSON_ID, AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)
        }
    }
})
