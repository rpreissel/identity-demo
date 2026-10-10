package com.example.identity.tools.auth_invite.internal

import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.directory.InvitationGrant
import com.example.identity.contract.tool_api.directory.Invitations
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

private const val PERSON = "P000000005"
private const val KVNR = "A123456789"
private const val CODE = "ABCD-EFGH-JKMN"
private const val WRONG_CODE = "WXYZ-WXYZ-WXYZ"
private val GRANT = InvitationGrant(invitation = InvitationId("invitation-hash"), process = "process-1", acr = AcrLevel.LOA2)

/** The one reason a wrong code, an unknown number and a rate-limited person all give. */
private val wrongCodeAnswer = Text("Nummer oder Einmalkennwort ungueltig")

/** One active tool session; the register opens [GRANT] for [PERSON] with [CODE] and nothing else. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = InMemoryToolSessionData().also { it.save(toolSessionId, AuthInviteLookupToolSession()) }
    val invitations = mockk<Invitations>().also {
        every { it.redeem(any(), any()) } returns null
        every { it.redeem(PartnerNumber(PERSON), CODE) } returns GRANT
    }
    val handler = AuthInviteLookupToolHandler(sessions, invitations)
}

/**
 * Pure unit test without Spring. The rule under test: a code alone opens nothing, and a rate-limited
 * person, an unknown number and a wrong code look the same.
 */
class AuthInviteLookupToolHandlerTest : BehaviorSpec({

    given("no auth-invite-lookup tool session yet") {
        val f = Fixture()

        `when`("a tool session starts") {
            val outcome = f.handler.start(ToolSessionId(UUID.randomUUID()))

            then("it asks for the number and the code at step auth") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "auth", stepData = MissingFields(listOf("kvnr", "code")))
            }
        }
    }

    given("an active session and a code that opens an invitation of the person") {
        val f = Fixture()

        `when`("the person's KVNR and the code arrive") {
            val outcome = f.handler.patch(f.toolSessionId, KVNR, null, CODE, PartnerNumber(PERSON), rateLimited = false)

            then("the channel is authenticated for the invitation at its level") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                    achievedAcr = AcrLevel.LOA2,
                    subject = Subject.Invitation(InvitationId("invitation-hash")),
                )
            }
        }
    }

    given("an active session and a wrong code for a known person") {
        val f = Fixture()

        `when`("the number and the code arrive") {
            val outcome = f.handler.patch(f.toolSessionId, KVNR, null, WRONG_CODE, PartnerNumber(PERSON), rateLimited = false)

            then("it fails and names the person tried") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(wrongCodeAnswer, attempted = Attempted.Person(PartnerNumber(PERSON)))
            }
        }
    }

    given("an active session and a rateLimited person with a valid code") {
        val f = Fixture()

        `when`("the number and the code arrive") {
            val outcome = f.handler.patch(f.toolSessionId, KVNR, null, CODE, PartnerNumber(PERSON), rateLimited = true)

            then("the answer is the one for a wrong code") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(wrongCodeAnswer, attempted = Attempted.Person(PartnerNumber(PERSON)))
            }

            then("the register is not asked") {
                verify(exactly = 0) { f.invitations.redeem(any(), any()) }
            }
        }
    }

    given("an active session and a number that resolves no person, with a valid code") {
        val f = Fixture()

        `when`("the number and the code arrive") {
            val outcome = f.handler.patch(f.toolSessionId, "Z999999999", null, CODE, personId = null, rateLimited = false)

            then("the answer is the one for a wrong code, without a person") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(wrongCodeAnswer, attempted = null)
            }

            then("the register is not asked") {
                verify(exactly = 0) { f.invitations.redeem(any(), any()) }
            }
        }
    }

    given("an active session and a number without a code") {
        val f = Fixture()

        `when`("the input arrives") {
            val outcome = f.handler.patch(f.toolSessionId, KVNR, null, null, PartnerNumber(PERSON), rateLimited = false)

            then("the step asks for the code again") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "auth", stepData = MissingFields(listOf("code")))
            }

            then("the register is not asked") {
                verify(exactly = 0) { f.invitations.redeem(any(), any()) }
            }
        }
    }

    given("an active session and an empty input") {
        val f = Fixture()

        `when`("the input arrives") {
            val outcome = f.handler.patch(f.toolSessionId, null, null, null, null, rateLimited = false)

            then("the step asks for the number and the code") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "auth", stepData = MissingFields(listOf("kvnr", "code")))
            }
        }
    }

    given("an unknown tool session") {
        val f = Fixture()

        `when`("input arrives for it") {
            val result = runCatching { f.handler.patch(ToolSessionId(UUID.randomUUID()), KVNR, null, CODE, PartnerNumber(PERSON), rateLimited = false) }

            then("it is refused") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }
})
