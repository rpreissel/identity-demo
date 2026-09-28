package com.example.identity.tools.ident_nect.internal

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.assertClaimsCovered
import com.example.identity.simulation.nect.NectAttributes
import com.example.identity.simulation.nect.NectCaseRef
import com.example.identity.simulation.nect.NectFailure
import com.example.identity.simulation.nect.NectIdent
import com.example.identity.simulation.nect.NectProcedure
import com.example.identity.simulation.nect.NectResult
import com.example.identity.tools.ident_nect.IdentNectDescriptor
import com.example.identity.tools.ident_nect.api.v1.NectRedirectStep
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, the repository and Nect mocked with MockK. Covers the case
 * binding, how each Nect result becomes an outcome, and the level, factors and claims per document.
 */
class IdentNectToolHandlerTest : BehaviorSpec({

    val repository = mockk<IdNectToolSessionRepository>()
    val nect = mockk<NectIdent>()
    val handler = IdentNectToolHandler(IdentNectDescriptor, repository, nect)
    val source = ClaimSource.of(IdentNectDescriptor.toolId)

    /** A tool session waiting for a fresh case; returns both ids. */
    fun waitingForCase(): Pair<UUID, UUID> {
        val toolSessionId = UUID.randomUUID()
        val caseId = UUID.randomUUID()
        every { repository.findById(toolSessionId) } returns Optional.of(IdNectToolSession(toolSessionId = toolSessionId, caseId = caseId))
        return toolSessionId to caseId
    }

    val fullAttributes = NectAttributes(
        name = "Mustermann",
        vorname = "Erika",
        geburtsdatum = LocalDate.of(1964, 8, 12),
        strasse = "Heidestraße 17",
        plz = "51147",
        ort = "Köln",
        restrictedId = "nect-pseudonym-1",
    )

    given("start()") {
        `when`("an ident-nect run begins") {
            val toolSessionId = UUID.randomUUID()
            val caseId = UUID.randomUUID()
            every { nect.createCase(NECT_CALLBACK_URI, NECT_REQUESTED) } returns NectCaseRef(caseId, "/nect/?case=$caseId")
            val saved = slot<IdNectToolSession>()
            every { repository.save(capture(saved)) } answers { saved.captured }
            val outcome = handler.start(toolSessionId)

            then("it binds the opened case to the session and redirects to Nect") {
                saved.captured.caseId shouldBe caseId
                outcome shouldBe ToolOutcome.InProgress(nextStep = "redirect", stepData = NectRedirectStep("/nect/?case=$caseId", caseId))
            }
        }
    }

    given("an ident-nect session waiting for its case") {
        `when`("the client reports a case id this session did not open") {
            val (toolSessionId, _) = waitingForCase()
            val foreignCase = UUID.randomUUID()
            val outcome = handler.patch(toolSessionId, caseId = foreignCase, retry = false)

            then("it fails naming nobody and never redeems the foreign case") {
                outcome shouldBe ToolOutcome.Failed.Identification(Text("Nect-Vorgang gehört nicht zu diesem Ablauf"), attemptedPersonId = null)
                verify(exactly = 0) { nect.redeem(foreignCase) }
            }
        }

        `when`("the client asks for a retry") {
            val (toolSessionId, oldCase) = waitingForCase()
            val newCase = UUID.randomUUID()
            every { nect.createCase(NECT_CALLBACK_URI, NECT_REQUESTED) } returns NectCaseRef(newCase, "/nect/?case=$newCase")
            val saved = slot<IdNectToolSession>()
            every { repository.save(capture(saved)) } answers { saved.captured }
            val outcome = handler.patch(toolSessionId, caseId = oldCase, retry = true)

            then("it opens a fresh case, rebinds the session and redirects again") {
                saved.captured.caseId shouldBe newCase
                outcome shouldBe ToolOutcome.InProgress(nextStep = "redirect", stepData = NectRedirectStep("/nect/?case=$newCase", newCase))
            }
        }

        `when`("Nect does not know the case or has already handed it out") {
            val (toolSessionId, caseId) = waitingForCase()
            every { nect.redeem(caseId) } returns null
            val outcome = handler.patch(toolSessionId, caseId, retry = false)

            then("it fails naming nobody") {
                outcome shouldBe ToolOutcome.Failed.Identification(Text("Nect-Vorgang unbekannt oder bereits eingelöst"), attemptedPersonId = null)
            }
        }

        `when`("the user has not finished at Nect yet") {
            val (toolSessionId, caseId) = waitingForCase()
            every { nect.redeem(caseId) } returns NectResult.Open
            val outcome = handler.patch(toolSessionId, caseId, retry = false)

            then("it fails as not finished") {
                outcome shouldBe ToolOutcome.Failed.Identification(Text("Nect-Vorgang noch nicht abgeschlossen"), attemptedPersonId = null)
            }
        }

        `when`("Nect reports a failed identification") {
            val (toolSessionId, caseId) = waitingForCase()
            every { nect.redeem(caseId) } returns NectResult.Failed(NectFailure.SELFIE_MISMATCH)
            val outcome = handler.patch(toolSessionId, caseId, retry = false)

            then("it words Nect's reason code itself and names nobody") {
                outcome shouldBe ToolOutcome.Failed.Identification(Text("Nect: Das Selfie passt nicht zum Passbild"), attemptedPersonId = null)
            }
        }
    }

    given("a case completed at Nect with the eID card") {
        val (toolSessionId, caseId) = waitingForCase()
        every { nect.redeem(caseId) } returns NectResult.Identified(NectProcedure.EID, fullAttributes)

        `when`("the client reports the case") {
            val outcome = handler.patch(toolSessionId, caseId, retry = false)

            then("it identifies at loa3 with possession plus knowledge") {
                val identified = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                identified.amr shouldBe listOf("nect-eid")
                identified.achievedAcr shouldBe AcrLevel.LOA3
                identified.factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
            }

            then("it asserts every attribute, including Nect's own card pseudonym, within the descriptor's claims") {
                val identified = outcome as ToolOutcome.Completed.Identified
                identified.claims shouldBe listOf(
                    Claim(AttributeType.FAMILY_NAME, "Mustermann", source, AcrLevel.LOA3),
                    Claim(AttributeType.GIVEN_NAMES, "Erika", source, AcrLevel.LOA3),
                    Claim(AttributeType.BIRTH_DATE, "1964-08-12", source, AcrLevel.LOA3),
                    Claim(AttributeType.STREET_ADDRESS, "Heidestraße 17", source, AcrLevel.LOA3),
                    Claim(AttributeType.POSTAL_CODE, "51147", source, AcrLevel.LOA3),
                    Claim(AttributeType.LOCALITY, "Köln", source, AcrLevel.LOA3),
                    Claim(AttributeType.NECT_RESTRICTED_ID, "nect-pseudonym-1", source, AcrLevel.LOA3),
                )
                identified.personId shouldBe null
                assertClaimsCovered(IdentNectDescriptor, identified.claims)
            }

            then("it records the case for the audit, without a document number") {
                (outcome as ToolOutcome.Completed.Identified).auditDetails shouldBe mapOf(
                    "provider" to "nect-mock",
                    "providerTxId" to caseId.toString(),
                    "toolSessionId" to toolSessionId.toString(),
                    "procedure" to "eid",
                )
            }
        }
    }

    given("a case completed at Nect with the passport") {
        val (toolSessionId, caseId) = waitingForCase()
        // A passport delivers no address; a pseudonym in the result is not the chip's and must not pass.
        every { nect.redeem(caseId) } returns NectResult.Identified(
            NectProcedure.EPASS,
            NectAttributes(name = "Mustermann", vorname = "Erika", geburtsdatum = LocalDate.of(1964, 8, 12), restrictedId = "not-from-a-passport"),
        )

        `when`("the client reports the case") {
            val outcome = handler.patch(toolSessionId, caseId, retry = false)

            then("it identifies at loa2 with possession plus inherence, asserting only what the passport delivers") {
                val identified = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                identified.amr shouldBe listOf("nect-epass")
                identified.achievedAcr shouldBe AcrLevel.LOA2
                identified.factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.INHERENCE)
                identified.claims.map { it.attributeType } shouldBe listOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)
                identified.claims.map { it.establishedAcr }.toSet() shouldBe setOf(AcrLevel.LOA2)
            }
        }
    }

    given("a case completed at Nect with the EUDI wallet") {
        val (toolSessionId, caseId) = waitingForCase()
        every { nect.redeem(caseId) } returns NectResult.Identified(NectProcedure.EUDI, fullAttributes)

        `when`("the client reports the case") {
            val outcome = handler.patch(toolSessionId, caseId, retry = false)

            then("it identifies at loa3 with possession plus knowledge and asserts no card pseudonym") {
                val identified = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Identified>()
                identified.amr shouldBe listOf("nect-eudi")
                identified.achievedAcr shouldBe AcrLevel.LOA3
                identified.factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
                identified.claims.map { it.attributeType } shouldBe listOf(
                    AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE,
                    AttributeType.STREET_ADDRESS, AttributeType.POSTAL_CODE, AttributeType.LOCALITY,
                )
            }
        }
    }
})
