package com.example.identity.tools.auth_email.internal.authemail

import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.TooManyRequestsException
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.simulation.mail.MailServer
import com.example.identity.tools.auth_email.AuthEmailDescriptor
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator
import com.example.identity.tools.auth_email.internal.EmailSendBudget
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.Optional
import java.util.UUID

/**
 * Pure unit test: no Spring context, repositories and the account directory mocked with MockK.
 * Covers persistence/outcome wiring only - the code-vs-state decision is covered by [AuthEmailFlowTest].
 */
class AuthEmailToolHandlerTest : BehaviorSpec({

    val toolDataRepository = mockk<AuthEmailToolSessionRepository>()
    val accountDirectory = mockk<AccountDirectory>()
    val emailCodeGenerator = EmailCodeGenerator("test-pepper")
    val sendBudget = mockk<EmailSendBudget>(relaxed = true).also { every { it.trySend(any()) } returns true }
    val mailServer = MailServer()
    val handler = AuthEmailToolHandler(AuthEmailDescriptor, toolDataRepository, accountDirectory, emailCodeGenerator, mailServer, sendBudget)

    given("start()") {
        `when`("the account has no confirmed email address") {
            every { accountDirectory.anchorValue(1L, AttributeType.EMAIL) } returns null
            val result = runCatching { handler.start(UUID.randomUUID(), accountId = 1L) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }

        `when`("the account has a confirmed email address") {
            val toolSessionId = UUID.randomUUID()
            every { accountDirectory.anchorValue(2L, AttributeType.EMAIL) } returns "max@example.com"
            val saved = slot<AuthEmailToolSession>()
            every { toolDataRepository.save(capture(saved)) } answers { saved.captured }
            val outcome = handler.start(toolSessionId, accountId = 2L)

            then("it persists a fresh code and asks for it at step auth") {
                val step = outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                step.nextStep shouldBe "auth"
                step.stepData shouldBe MissingFields(listOf("code"))
                saved.captured.toolSessionId shouldBe toolSessionId
                saved.captured.issuedCodeHash.shouldNotBeNull()
            }

            then("it mails the same code it reveals as the demo value") {
                val step = outcome as ToolOutcome.InProgress
                val mail = mailServer.outbox().first { it.address == "max@example.com" }
                step.demo?.get("tan") shouldBe mail.code
            }
        }

        `when`("the address has used up its send budget") {
            val gateway = MailServer()
            val throttledHandler = AuthEmailToolHandler(AuthEmailDescriptor, toolDataRepository, accountDirectory, emailCodeGenerator, gateway, sendBudget)
            every { accountDirectory.anchorValue(3L, AttributeType.EMAIL) } returns "flooded@example.com"
            every { sendBudget.trySend("flooded@example.com") } returns false
            val result = runCatching { throttledHandler.start(UUID.randomUUID(), accountId = 3L) }

            then("it refuses with TooManyRequestsException and sends nothing") {
                shouldThrow<TooManyRequestsException> { result.getOrThrow() }
                gateway.outbox().shouldBeEmpty()
            }
        }
    }

    given("an active auth-email tool session with a pending code") {
        val toolSessionId = UUID.randomUUID()
        val issued = emailCodeGenerator.issue()
        every { toolDataRepository.findById(toolSessionId) } returns
            Optional.of(AuthEmailToolSession(toolSessionId = toolSessionId, issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt))
        every { accountDirectory.anchorValue(42L, AttributeType.EMAIL) } returns "max@example.com"

        `when`("confirming with the correct code") {
            val outcome = handler.patch(toolSessionId, issued.plainCode, accountId = 42L)

            then("it authenticates at the descriptor's own maxAcr and factorTypes") {
                val authenticated = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                authenticated.amr shouldBe listOf("email")
                authenticated.achievedAcr shouldBe AuthEmailDescriptor.maxAcr
                authenticated.factorTypes shouldBe AuthEmailDescriptor.factorTypes
                authenticated.accountId shouldBe null
            }

            then("the address's send budget starts over: whoever asked received the code") {
                verify { sendBudget.received("max@example.com") }
            }
        }

        `when`("submitting a wrong code") {
            val outcome = handler.patch(toolSessionId, "000000", accountId = 43L)

            then("it fails against the account the channel already knows") {
                outcome shouldBe ToolOutcome.Failed.IdentifiedAuth(Text("Code ungueltig oder abgelaufen"))
            }

            then("it does not look up the address, so no budget is reset") {
                verify(exactly = 0) { accountDirectory.anchorValue(43L, AttributeType.EMAIL) }
            }
        }

        `when`("submitting no code at all") {
            val outcome = handler.patch(toolSessionId, null, accountId = 42L)

            then("it describes the unchanged step instead of failing") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "auth", stepData = MissingFields(listOf("code")))
            }
        }
    }
})
