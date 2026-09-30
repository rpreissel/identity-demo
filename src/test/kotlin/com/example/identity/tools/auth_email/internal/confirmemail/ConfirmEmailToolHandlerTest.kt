package com.example.identity.tools.auth_email.internal.confirmemail

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.simulation.mail.MailServer
import com.example.identity.tools.auth_email.ConfirmEmailDescriptor
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator
import com.example.identity.tools.auth_email.internal.EmailSendLimit
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.TooManyRequestsException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories mocked with MockK. Covers persistence/outcome
 * wiring only - the decision branches (invalid email, wrong code, ambiguous combinations) are
 * covered by [ConfirmEmailFlowTest].
 */
class ConfirmEmailToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<ConfirmEmailToolSessionRepository>()
    val emailCodeGenerator = EmailCodeGenerator("test-pepper", clock = TEST_CLOCK)
    val sendLimit = mockk<EmailSendLimit>(relaxed = true).also { every { it.trySend(any()) } returns true }
    val handler = ConfirmEmailToolHandler(ConfirmEmailDescriptor, toolDataRepository, emailCodeGenerator, MailServer(clock = TEST_CLOCK), sendLimit, clock = TEST_CLOCK)
    val toolSessionId = ToolSessionId(UUID.randomUUID())

    given("an active enroll-email tool session with no email yet") {
        val data = ConfirmEmailToolSession(toolSessionId = toolSessionId, createdAt = TEST_NOW)
        every { toolDataRepository.findByToolSessionId(toolSessionId) } returns data

        `when`("submitting an email") {
            val saved = slot<ConfirmEmailToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }

            then("it persists the address and a fresh code, asking for codeInput") {
                val outcome = handler.patch(toolSessionId, email = "max@example.com", code = null)

                outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                outcome.nextStep shouldBe "codeInput"
                saved.captured.email shouldBe "max@example.com"
                saved.captured.issuedCodeHash.shouldNotBeNull()
            }
        }

        `when`("the address has used up its send budget") {
            every { sendLimit.trySend("flooded@example.com") } returns false
            val result = runCatching { handler.patch(toolSessionId, email = "flooded@example.com", code = null) }

            then("it refuses with 429 and sends no code - nothing was guessed, the caller chose the address") {
                shouldThrow<TooManyRequestsException> { result.getOrThrow() }
            }
        }
    }

    given("an active enroll-email tool session with a pending code") {
        val issued = emailCodeGenerator.issue()
        val data = ConfirmEmailToolSession(toolSessionId = toolSessionId, email = "max@example.com", issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt, createdAt = TEST_NOW)
        every { toolDataRepository.findByToolSessionId(toolSessionId) } returns data

            `when`("confirming with the correct code") {
                then("it attests the address without creating a credential") {
                    val outcome = handler.patch(toolSessionId, email = null, code = issued.plainCode)

                    outcome.shouldBeInstanceOf<ToolOutcome.Completed.Attested>()
                    outcome.claims shouldBe listOf(
                        Claim(AttributeType.EMAIL, "max@example.com", ClaimSource(ConfirmEmailDescriptor.toolId.value), ConfirmEmailDescriptor.maxAcr)
                    )
                    // No amr and no factor: confirming an address is not an authentication proof,
                    // so it must not raise the channel's assurance.
                    outcome.amr shouldBe emptyList()
                    outcome.factorTypes shouldBe emptySet()
                }

                then("the address's send budget starts over: whoever asked received the code") {
                    handler.patch(toolSessionId, email = null, code = issued.plainCode)

                    verify { sendLimit.received("max@example.com") }
                }
            }
    }
})
