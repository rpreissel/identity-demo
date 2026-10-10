package com.example.identity.tools.ident_nect.internal

import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.ident_nect.NECT_RESTRICTED_ID
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
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
import com.example.identity.tools.ident_nect.api.v1.NectRedirectStep
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.LocalDate
import java.util.UUID

/**
 * Pure unit test: no Spring context, the session data kept in memory, Nect mocked with MockK. Covers the case
 * binding, how each Nect result becomes an outcome, and the level, factors and claims per document.
 */
class IdentNectToolHandlerTest : BehaviorSpec({

    val sessions = InMemoryToolSessionData()
    val nect = mockk<NectIdent>()
    val handler = IdentNectToolHandler(sessions, nect)

    /** The session as the handler last saved it. */
    fun stored(toolSessionId: ToolSessionId): IdentNectToolSession = sessions.stored(toolSessionId)
    val source = ClaimSource(tool("ident-nect").toolId.value)

    /** A tool session waiting for a fresh case; returns both ids. */
    fun waitingForCase(): Pair<ToolSessionId, UUID> {
        val toolSessionId = ToolSessionId(UUID.randomUUID())
        val caseId = UUID.randomUUID()
        sessions.save(toolSessionId, IdentNectToolSession(caseId = caseId))
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

    given("the app channel, which Nect sends back to its own callback") {
        `when`("an ident-nect run begins") {
            val toolSessionId = ToolSessionId(UUID.randomUUID())
            val caseId = UUID.randomUUID()
            every { nect.createCase(NECT_CALLBACK_URI, NECT_REQUESTED) } returns NectCaseRef(caseId, "/nect/?case=$caseId")
            val outcome = handler.start(toolSessionId)

            then("it binds the opened case to the session and redirects to Nect") {
                stored(toolSessionId).caseId shouldBe caseId
                outcome shouldBe ToolOutcome.InProgress(nextStep = "redirect", stepData = NectRedirectStep("/nect/?case=$caseId", caseId))
            }
        }
    }

    given("a channel that names where Nect sends the user back to") {
        val prefixes = IdentNectProperties(returnUriPrefixes = listOf("https://kc.test/realms/"))
        val webHandler = IdentNectToolHandler(sessions, nect, properties = prefixes)
        val actionUrl = "https://kc.test/realms/Demo/login-actions/authenticate?session_code=c1&execution=e1&client_id=web&tab_id=t1"

        `when`("the address lies under a configured prefix") {
            val toolSessionId = ToolSessionId(UUID.randomUUID())
            val caseId = UUID.randomUUID()
            every { nect.createCase(actionUrl, NECT_REQUESTED) } returns NectCaseRef(caseId, "/nect/?case=$caseId")
            webHandler.start(toolSessionId, returnUri = actionUrl)

            then("the case is opened with that address, and the session remembers it") {
                stored(toolSessionId).returnUri shouldBe actionUrl
                verify(exactly = 1) { nect.createCase(actionUrl, NECT_REQUESTED) }
            }
        }

        `when`("a retry follows on such a session") {
            val toolSessionId = ToolSessionId(UUID.randomUUID())
            val oldCase = UUID.randomUUID()
            val newCase = UUID.randomUUID()
            sessions.save(toolSessionId, IdentNectToolSession(caseId = oldCase, returnUri = actionUrl))
            every { nect.createCase(actionUrl, NECT_REQUESTED) } returns NectCaseRef(newCase, "/nect/?case=$newCase")
            val outcome = webHandler.patch(toolSessionId, caseId = oldCase, retry = true)

            then("the fresh case keeps the same return address") {
                // newCase only comes from the stub for actionUrl - the outcome proves which address was used.
                outcome shouldBe ToolOutcome.InProgress(nextStep = "redirect", stepData = NectRedirectStep("/nect/?case=$newCase", newCase))
            }
        }

        `when`("a retry names a fresh address of its own") {
            val toolSessionId = ToolSessionId(UUID.randomUUID())
            val oldCase = UUID.randomUUID()
            val newCase = UUID.randomUUID()
            val freshUrl = "https://kc.test/realms/Demo/login-actions/authenticate?session_code=c2&execution=e1&client_id=web&tab_id=t1"
            sessions.save(toolSessionId, IdentNectToolSession(caseId = oldCase, returnUri = actionUrl))
            every { nect.createCase(freshUrl, NECT_REQUESTED) } returns NectCaseRef(newCase, "/nect/?case=$newCase")
            val outcome = webHandler.patch(toolSessionId, caseId = oldCase, retry = true, returnUri = freshUrl)

            then("the fresh case goes back there, and the session remembers it for the next retry") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "redirect", stepData = NectRedirectStep("/nect/?case=$newCase", newCase))
                stored(toolSessionId).returnUri shouldBe freshUrl
            }
        }

        `when`("a retry names an address outside the prefixes") {
            val toolSessionId = ToolSessionId(UUID.randomUUID())
            sessions.save(toolSessionId, IdentNectToolSession(caseId = UUID.randomUUID(), returnUri = actionUrl))
            val result = runCatching { webHandler.patch(toolSessionId, caseId = null, retry = true, returnUri = "https://attacker.example/return") }

            then("it is rejected as bad input, and no case is opened") {
                shouldThrow<IllegalArgumentException> { result.getOrThrow() }
                verify(exactly = 0) { nect.createCase("https://attacker.example/return", any()) }
            }
        }

        `when`("a start names an address elsewhere") {
            val elsewhere = "https://elsewhere.example/return"
            val result = runCatching { webHandler.start(ToolSessionId(UUID.randomUUID()), returnUri = elsewhere) }

            then("the start is rejected as bad input, and no case is opened") {
                shouldThrow<IllegalArgumentException> { result.getOrThrow() }
                verify(exactly = 0) { nect.createCase(elsewhere, any()) }
            }
        }
    }

    given("no return address prefix configured at all") {
        val actionUrl = "https://kc.test/realms/Demo/login-actions/authenticate?session_code=c1&execution=e1&client_id=web&tab_id=t1"

        `when`("a start names a channel address") {
            val result = runCatching { handler.start(ToolSessionId(UUID.randomUUID()), returnUri = actionUrl) }

            then("it is rejected - only the app channel's own address is left") {
                shouldThrow<IllegalArgumentException> { result.getOrThrow() }
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
            val outcome = handler.patch(toolSessionId, caseId = oldCase, retry = true)

            then("it opens a fresh case, rebinds the session and redirects again") {
                stored(toolSessionId).caseId shouldBe newCase
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
            val identified = handler.patch(toolSessionId, caseId, retry = false).shouldBeInstanceOf<ToolOutcome.Completed.Identified>()

            then("it identifies at loa3 with possession plus knowledge") {
                identified.amr shouldBe listOf("nect-eid")
                identified.achievedAcr shouldBe AcrLevel.LOA3
                identified.factorTypes shouldBe setOf(FactorType.POSSESSION, FactorType.KNOWLEDGE)
            }

            then("it asserts every attribute, including Nect's own card pseudonym, within the descriptor's claims") {
                identified.claims shouldBe listOf(
                    Claim(AttributeType.FAMILY_NAME, "Mustermann", source, AcrLevel.LOA3),
                    Claim(AttributeType.GIVEN_NAMES, "Erika", source, AcrLevel.LOA3),
                    Claim(AttributeType.BIRTH_DATE, "1964-08-12", source, AcrLevel.LOA3),
                    Claim(AttributeType.STREET_ADDRESS, "Heidestraße 17", source, AcrLevel.LOA3),
                    Claim(AttributeType.POSTAL_CODE, "51147", source, AcrLevel.LOA3),
                    Claim(AttributeType.LOCALITY, "Köln", source, AcrLevel.LOA3),
                    Claim(NECT_RESTRICTED_ID, "nect-pseudonym-1", source, AcrLevel.LOA3),
                )
                identified.personId shouldBe null
                assertClaimsCovered(tool("ident-nect"), identified.claims)
            }

            then("it records the case for the audit, without a document number") {
                identified.auditDetails shouldBe mapOf(
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
