package com.example.identity.tools.auth_email.internal.confirmemail

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.otp.OneTimeCodes
import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.TEST_CLOCK
import com.example.identity.simulation.mail.MailServer
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator
import com.example.identity.tools.auth_email.internal.EmailSendLimit
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.TooManyRequestsException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

/** No tool session exists and the send budget is open until a test changes that. */
private class Fixture {
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val sessions = InMemoryToolSessionData()

    /** The session as the handler last saved it. */
    val saved: ConfirmEmailToolSession get() = sessions.stored(toolSessionId)
    val codes = EmailCodeGenerator("test-pepper", clock = TEST_CLOCK)
    val sendLimit = mockk<EmailSendLimit>(relaxed = true).also { every { it.trySend(any()) } returns true }
    val mailServer = MailServer(clock = TEST_CLOCK)
    val handler = ConfirmEmailToolHandler(sessions, codes, mailServer, sendLimit)

    fun withSessionAwaitingEmail() = apply {
        sessions.save(toolSessionId, ConfirmEmailToolSession())
    }

    fun withSendBudgetUsedUp(address: String) = apply {
        every { sendLimit.trySend(address) } returns false
    }

    /** Persists a pending code for [address] and returns it. */
    fun withPendingCode(address: String): OneTimeCodes.Issued = codes.issue().also { issued ->
        sessions.save(toolSessionId, ConfirmEmailToolSession(email = address, issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt))
    }
}

/**
 * Pure unit test: no Spring context, the session data kept in memory. Covers persistence/outcome
 * wiring only - the decision branches (invalid email, wrong code, ambiguous combinations) are
 * covered by [ConfirmEmailFlowTest].
 */
class ConfirmEmailToolHandlerTest : BehaviorSpec({

    given("no confirm-email tool session yet") {
        val f = Fixture()

        `when`("a tool session starts") {
            val outcome = f.handler.start(f.toolSessionId)

            then("it asks for the email at step input") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("email")), demo = emptyMap())
            }
        }
    }

    given("an active confirm-email tool session with no email yet") {
        val f = Fixture().withSessionAwaitingEmail()

        `when`("submitting an email") {
            val outcome = f.handler.patch(f.toolSessionId, email = "max@example.com", code = null)
            val mail = f.mailServer.outbox().single()

            then("it mails a code to the address and asks for it at step codeInput, revealing it as the demo value") {
                mail.address shouldBe "max@example.com"
                outcome shouldBe ToolOutcome.InProgress(nextStep = "codeInput", stepData = MissingFields(listOf("code")), demo = mapOf("tan" to mail.code))
            }

            then("it persists the address and the hash of the mailed code") {
                f.saved.email shouldBe "max@example.com"
                f.codes.matches(mail.code, f.saved.issuedCodeHash, f.saved.codeExpiresAt) shouldBe true
            }
        }
    }

    given("an active confirm-email tool session with no email yet, and an address that has used up its send budget") {
        val f = Fixture().withSessionAwaitingEmail().withSendBudgetUsedUp("flooded@example.com")

        `when`("submitting that address") {
            val result = runCatching { f.handler.patch(f.toolSessionId, email = "flooded@example.com", code = null) }

            then("it refuses with 429 - nothing was guessed, the caller chose the address") {
                shouldThrow<TooManyRequestsException> { result.getOrThrow() }
            }

            then("it sends no code") {
                f.mailServer.outbox().shouldBeEmpty()
            }
        }
    }

    given("an active confirm-email tool session with a pending code") {
        val f = Fixture()
        val issued = f.withPendingCode("max@example.com")

        `when`("confirming with the correct code") {
            val outcome = f.handler.patch(f.toolSessionId, email = null, code = issued.plain)

            then("it attests the address without creating a credential") {
                outcome shouldBe ToolOutcome.Completed.Attested(
                    claims = listOf(
                        Claim(AttributeType.EMAIL, "max@example.com", ClaimSource(tool("confirm-email").toolId.value), tool("confirm-email").maxAcr)
                    )
                )
            }

            then("the address's send budget starts over: whoever asked received the code") {
                verify { f.sendLimit.received("max@example.com") }
            }
        }
    }
})
