package com.example.identity.tools.auth_email.internal.authemail

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
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
import com.example.identity.tools.auth_email.internal.EmailSendLimit
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID

private const val ADDRESS = "max@example.com"

/** The account has no confirmed address and the send budget is open until a test changes that. */
private class Fixture {
    val accountId = AccountId(42L)
    val toolSessionId: ToolSessionId = ToolSessionId(UUID.randomUUID())
    val saved = slot<AuthEmailToolSession>()
    val sessions = mockk<AuthEmailToolSessionRepository>().also {
        every { it.save(capture(saved)) } answers { saved.captured }
    }
    val accountDirectory = mockk<AccountDirectory>().also {
        every { it.anchorValue(any(), AttributeType.EMAIL) } returns null
    }
    val codes = EmailCodeGenerator("test-pepper", clock = TEST_CLOCK)
    val sendLimit = mockk<EmailSendLimit>(relaxed = true).also { every { it.trySend(any()) } returns true }
    val mailServer = MailServer(clock = TEST_CLOCK)
    val handler = AuthEmailToolHandler(AuthEmailDescriptor, sessions, accountDirectory, codes, mailServer, sendLimit, clock = TEST_CLOCK)

    fun withConfirmedAddress() = apply {
        every { accountDirectory.anchorValue(accountId, AttributeType.EMAIL) } returns ADDRESS
    }

    fun withSendBudgetUsedUp() = apply {
        every { sendLimit.trySend(ADDRESS) } returns false
    }

    /** Persists a pending code for [toolSessionId] and returns it. */
    fun withPendingCode(): EmailCodeGenerator.Issued = codes.issue().also { issued ->
        every { sessions.findByToolSessionId(toolSessionId) } returns
            AuthEmailToolSession(toolSessionId = toolSessionId, issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt, createdAt = TEST_NOW)
    }
}

/**
 * Pure unit test: no Spring context, repositories and the account directory mocked with MockK.
 * Covers persistence/outcome wiring only - the code-vs-state decision is covered by [AuthEmailFlowTest].
 */
class AuthEmailToolHandlerTest : BehaviorSpec({

    given("an account without a confirmed email address") {
        val f = Fixture()

        `when`("a tool session starts for it") {
            val result = runCatching { f.handler.start(f.toolSessionId, f.accountId) }

            then("it throws UnresolvableReferenceException") {
                shouldThrow<UnresolvableReferenceException> { result.getOrThrow() }
            }
        }
    }

    given("an account with a confirmed email address") {
        val f = Fixture().withConfirmedAddress()

        `when`("a tool session starts for it") {
            val outcome = f.handler.start(f.toolSessionId, f.accountId)
            val mail = f.mailServer.outbox().single()

            then("it mails a code to the address and asks for it at step auth, revealing it as the demo value") {
                mail.address shouldBe ADDRESS
                outcome shouldBe ToolOutcome.InProgress(nextStep = "auth", stepData = MissingFields(listOf("code")), demo = mapOf("tan" to mail.code))
            }

            then("it persists the hash of the mailed code for this tool session") {
                f.saved.captured.toolSessionId shouldBe f.toolSessionId
                f.codes.matches(mail.code, f.saved.captured.issuedCodeHash, f.saved.captured.codeExpiresAt) shouldBe true
            }
        }
    }

    given("an account whose address has used up its send budget") {
        val f = Fixture().withConfirmedAddress().withSendBudgetUsedUp()

        `when`("a tool session starts for it") {
            val result = runCatching { f.handler.start(f.toolSessionId, f.accountId) }

            then("it refuses with TooManyRequestsException") {
                shouldThrow<TooManyRequestsException> { result.getOrThrow() }
            }

            then("it sends nothing") {
                f.mailServer.outbox().shouldBeEmpty()
            }
        }
    }

    given("an active auth-email tool session with a pending code") {
        val f = Fixture().withConfirmedAddress()
        val issued = f.withPendingCode()

        `when`("confirming with the correct code") {
            val outcome = f.handler.patch(f.toolSessionId, issued.plainCode, f.accountId)

            then("it authenticates at the descriptor's own maxAcr and factorTypes") {
                val authenticated = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                authenticated.amr shouldBe listOf("email")
                authenticated.achievedAcr shouldBe AuthEmailDescriptor.maxAcr
                authenticated.factorTypes shouldBe AuthEmailDescriptor.factorTypes
                authenticated.subject shouldBe null
            }

            then("the address's send budget starts over: whoever asked received the code") {
                verify { f.sendLimit.received(ADDRESS) }
            }
        }
    }

    given("another active auth-email tool session with a pending code") {
        val f = Fixture().withConfirmedAddress()
        f.withPendingCode()

        `when`("submitting a wrong code") {
            val outcome = f.handler.patch(f.toolSessionId, "000000", f.accountId)

            then("it fails against the account the channel already knows") {
                outcome shouldBe ToolOutcome.Failed.KnownAccountAuth(Text("Code ungueltig oder abgelaufen"))
            }

            then("it does not look up the address, so no budget is reset") {
                verify(exactly = 0) { f.accountDirectory.anchorValue(any(), AttributeType.EMAIL) }
            }
        }

        `when`("submitting no code at all") {
            val outcome = f.handler.patch(f.toolSessionId, null, f.accountId)

            then("it describes the unchanged step instead of failing") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "auth", stepData = MissingFields(listOf("code")))
            }
        }
    }
})
