package com.example.identity.tools.ident_eid.internal

import com.example.identity.tools.ident_eid.IdentEidDescriptor
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
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Pins the claims a successful ident-eid run asserts: what the card showed, including the address,
 * each under the tool's own trust anchor. No PERSON_ID and no KVNR: a card carries neither, and
 * binding one is `ident-kvnr`'s act (ADR-18). The restricted_id claim becomes the replaceable
 * recognition anchor for a later eid run (ADR-19).
 */
class IdentEidToolHandlerTest : BehaviorSpec({

    val toolSessionId = UUID.randomUUID()
    val repository = mockk<IdentEidToolSessionRepository>()
    val handler = IdentEidToolHandler(IdentEidDescriptor, repository)

    given("an ident-eid session with the card read, waiting for the PIN") {
        val data = IdentEidToolSession(
            toolSessionId = toolSessionId,
            familyName = "Muster",
            givenNames = "Max",
            birthDate = LocalDate.of(1970, 1, 1),
            streetAddress = "Musterweg 1",
            postalCode = "12345",
            locality = "Musterstadt",
            restrictedId = "T0103005K1D5S0V8T9W6UM2RTX"
        )
        every { repository.findById(toolSessionId) } returns Optional.of(data)
        every { repository.save(any()) } returns data

        `when`("the correct mock PIN arrives") {
            val outcome = handler.patch(toolSessionId, EidPatchFields(pin = IdentEidFlow.MOCK_PIN))

            then("it attests every card attribute as a claim under its own tool anchor") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                outcome.claims shouldBe listOf(
                    Claim(AttributeType.FAMILY_NAME, "Muster", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.GIVEN_NAMES, "Max", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.BIRTH_DATE, "1970-01-01", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.STREET_ADDRESS, "Musterweg 1", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.POSTAL_CODE, "12345", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.LOCALITY, "Musterstadt", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr),
                    Claim(AttributeType.EID_RESTRICTED_ID, "T0103005K1D5S0V8T9W6UM2RTX", ClaimSource.of(IdentEidDescriptor.toolId), IdentEidDescriptor.maxAcr)
                )
            }

            then("it resolves nobody - no person reference is asserted") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                outcome.personId.shouldBeNull()
            }

            then("the audit blob carries only what no claim can - provider, tx ids, evidence hash") {
                outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                outcome.auditDetails?.get("locality").shouldBeNull()
                // Never the document number (§ 20 PAuswG); it only goes into the evidence hash.
                outcome.auditDetails?.get("documentNumber").shouldBeNull()
                outcome.auditDetails?.get("evidenceHash").shouldBeInstanceOf<String>().shouldStartWith("sha256:")
            }
        }

        `when`("a wrong PIN arrives") {
            then("it fails without naming a person - there is none to throttle against") {
                val outcome = handler.patch(toolSessionId, EidPatchFields(pin = "000000"))

                outcome.shouldBeInstanceOf<ToolOutcome.Failed.Identification>()
                outcome.attemptedPersonId.shouldBeNull()
            }
        }
    }
})
