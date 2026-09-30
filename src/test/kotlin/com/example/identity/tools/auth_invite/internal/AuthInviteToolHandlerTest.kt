package com.example.identity.tools.auth_invite.internal

import com.example.identity.contract.tool_api.ids.InvitationId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.directory.InvitationGrant
import com.example.identity.contract.tool_api.directory.Invitations
import com.example.identity.tools.auth_invite.AuthInviteDescriptor
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

private const val PERSON = "P000000005"
private const val KVNR = "A123456789"
private const val CODE = "ABCD-EFGH-JKMN"
private const val WRONG_CODE = "WXYZ-WXYZ-WXYZ"
private val GRANT = InvitationGrant(invitation = InvitationId("invitation-hash"), process = "process-1", acr = AcrLevel.LOA2)

/** One active tool session; the register opens [GRANT] for [PERSON] with [CODE] and nothing else. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = mockk<AuthInviteToolSessionRepository>().also {
        every { it.findByToolSessionId(any()) } returns null
        every { it.findByToolSessionId(toolSessionId) } returns AuthInviteToolSession(toolSessionId, TEST_NOW)
    }
    val invitations = mockk<Invitations>().also {
        every { it.redeem(any(), any()) } returns null
        every { it.redeem(PartnerNumber(PERSON), CODE) } returns GRANT
    }
    val handler = AuthInviteToolHandler(AuthInviteDescriptor, sessions, invitations, TEST_CLOCK)
}

/**
 * Pure unit test without Spring. The rule under test: a code alone opens nothing, and a rate-limited
 * person, an unknown number and a wrong code look the same.
 */
class AuthInviteToolHandlerTest : BehaviorSpec({

    given("an active session and a code that opens an invitation of the person") {
        val f = Fixture()

        `when`("the person's KVNR and the code arrive") {
            val outcome = f.handler.patch(f.toolSessionId, KVNR, null, CODE, PartnerNumber(PERSON), rateLimited = false)

            then("the channel is authenticated for the invitation at its level") {
                outcome shouldBe ToolOutcome.Completed.Authenticated(
                    amr = listOf("invite"),
                    achievedAcr = AcrLevel.LOA2,
                    factorTypes = setOf(FactorType.POSSESSION),
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
                val failed = outcome.shouldBeInstanceOf<ToolOutcome.Failed.AccountLookupAuth>()
                failed.reason.template shouldBe "Nummer oder Einmalkennwort ungueltig"
                failed.attempted shouldBe Attempted.Person(PartnerNumber(PERSON))
            }
        }
    }

    given("an active session and a rateLimited person with a valid code") {
        val f = Fixture()

        `when`("the number and the code arrive") {
            val outcome = f.handler.patch(f.toolSessionId, KVNR, null, CODE, PartnerNumber(PERSON), rateLimited = true)

            then("the answer is the one for a wrong code") {
                val failed = outcome.shouldBeInstanceOf<ToolOutcome.Failed.AccountLookupAuth>()
                failed.reason.template shouldBe "Nummer oder Einmalkennwort ungueltig"
                failed.attempted shouldBe Attempted.Person(PartnerNumber(PERSON))
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
                val failed = outcome.shouldBeInstanceOf<ToolOutcome.Failed.AccountLookupAuth>()
                failed.reason.template shouldBe "Nummer oder Einmalkennwort ungueltig"
                failed.attempted shouldBe null
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
