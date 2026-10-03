package com.example.identity.tools.auth_email.internal.authemaillookup

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.contract.tool_api.Subject
import com.example.identity.TEST_CLOCK
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.simulation.mail.MailServer
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator
import com.example.identity.tools.auth_email.internal.EmailSendLimit
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID

/**
 * Pure unit test: no Spring context, the session data kept in memory, the account directory mocked with MockK.
 * Covers persistence/outcome wiring and the enumeration-neutral answers of [AuthEmailLookupToolHandler.submitEmail];
 * the code-vs-state decision is covered by [AuthEmailLookupFlowTest].
 */
class AuthEmailLookupToolHandlerTest : BehaviorSpec({

    val sessions = InMemoryToolSessionData()
    val accountDirectory = mockk<AccountDirectory>()
    val emailCodeGenerator = EmailCodeGenerator("test-pepper", clock = TEST_CLOCK)
    val sendLimit = mockk<EmailSendLimit>(relaxed = true).also { every { it.trySend(any()) } returns true }
    val mailServer = MailServer(clock = TEST_CLOCK)
    val handler = AuthEmailLookupToolHandler(sessions, accountDirectory, emailCodeGenerator, mailServer, sendLimit)

    // What every unresolved submission answers: the code step, no demo code, nothing naming an account.
    val neutralAnswer = ToolOutcome.InProgress(nextStep = "codeInput", stepData = MissingFields(listOf("code")), demo = emptyMap())

    /** A fresh session awaiting the email; each submission gets its own, so they cannot see each other's writes. */
    fun awaitingEmail(): ToolSessionId {
        val toolSessionId = ToolSessionId(UUID.randomUUID())
        sessions.save(toolSessionId, AuthEmailLookupToolSession())
        return toolSessionId
    }

    /** The session as the handler last saved it. */
    fun stored(toolSessionId: ToolSessionId): AuthEmailLookupToolSession = sessions.stored(toolSessionId)

    given("no auth-email-lookup tool session yet") {
        `when`("a lookup login begins") {
            val started = ToolSessionId(UUID.randomUUID())
            val outcome = handler.start(started)
            val saved = stored(started)

            then("it asks for the email at step auth and stores no code yet") {
                outcome shouldBe ToolOutcome.InProgress(nextStep = "auth", stepData = MissingFields(listOf("email")), demo = emptyMap())
                saved.issuedCodeHash shouldBe null
                saved.accountId shouldBe null
            }
        }
    }

    given("an auth-email-lookup tool session awaiting the email") {
        `when`("the email resolves to an account with a confirmed address") {
            val toolSessionId = awaitingEmail()
            every { accountDirectory.resolveByAnchor(AttributeType.EMAIL, "max@example.com") } returns AccountId(42L)
            every { accountDirectory.anchorValue(AccountId(42L), AttributeType.EMAIL) } returns "max@example.com"
            val outcome = handler.submitEmail(toolSessionId, "max@example.com", locked = false)

            then("it stores the account and a fresh code, and mails the code it reveals as the demo value") {
                val step = outcome.shouldBeInstanceOf<ToolOutcome.InProgress>()
                step.nextStep shouldBe "codeInput"
                stored(toolSessionId).accountId shouldBe AccountId(42)
                stored(toolSessionId).issuedCodeHash.shouldNotBeNull()
                step.demo?.get("tan") shouldBe mailServer.outbox().first { it.address == "max@example.com" }.code
            }
        }

        `when`("the email resolves to nothing (enumeration protection)") {
            val toolSessionId = awaitingEmail()
            every { accountDirectory.resolveByAnchor(AttributeType.EMAIL, "nobody@example.com") } returns null
            val outcome = handler.submitEmail(toolSessionId, "nobody@example.com", locked = false)

            then("it still issues a code, but sends none, reveals none and stores no account") {
                outcome shouldBe neutralAnswer
                stored(toolSessionId).issuedCodeHash.shouldNotBeNull()
                stored(toolSessionId).accountId shouldBe null
                mailServer.outbox().filter { it.address == "nobody@example.com" }.shouldBeEmpty()
            }
        }

        `when`("the email resolves to a locked account") {
            val toolSessionId = awaitingEmail()
            every { accountDirectory.resolveByAnchor(AttributeType.EMAIL, "locked@example.com") } returns AccountId(43L)
            every { accountDirectory.anchorValue(AccountId(43L), AttributeType.EMAIL) } returns "locked@example.com"
            val outcome = handler.submitEmail(toolSessionId, "locked@example.com", locked = true)

            then("it answers exactly like for an unknown address and stores no account") {
                outcome shouldBe neutralAnswer
                stored(toolSessionId).accountId shouldBe null
                mailServer.outbox().filter { it.address == "locked@example.com" }.shouldBeEmpty()
            }

            then("it spends nothing from the address's send budget") {
                verify(exactly = 0) { sendLimit.trySend("locked@example.com") }
            }
        }

        `when`("the account's address has used up its send budget") {
            val toolSessionId = awaitingEmail()
            every { accountDirectory.resolveByAnchor(AttributeType.EMAIL, "flooded@example.com") } returns AccountId(44L)
            every { accountDirectory.anchorValue(AccountId(44L), AttributeType.EMAIL) } returns "flooded@example.com"
            every { sendLimit.trySend("flooded@example.com") } returns false
            val outcome = handler.submitEmail(toolSessionId, "flooded@example.com", locked = false)

            then("it answers exactly like for an unknown address and stores no account") {
                outcome shouldBe neutralAnswer
                stored(toolSessionId).accountId shouldBe null
                mailServer.outbox().filter { it.address == "flooded@example.com" }.shouldBeEmpty()
            }
        }
    }

    given("a resolved account with a pending code") {
        val toolSessionId = ToolSessionId(UUID.randomUUID())
        val issued = emailCodeGenerator.issue()
        sessions.save(toolSessionId, AuthEmailLookupToolSession(accountId = AccountId(42L), issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt))
        every { accountDirectory.anchorValue(AccountId(42L), AttributeType.EMAIL) } returns "max@example.com"

        `when`("confirming with the correct code") {
            val outcome = handler.patch(toolSessionId, issued.plainCode)

            then("it authenticates for that account at its tool's own level and factors") {
                val authenticated = outcome.shouldBeInstanceOf<ToolOutcome.Completed.Authenticated>()
                authenticated.subject shouldBe Subject.Account(AccountId(42L))
                authenticated.amr shouldBe null
                authenticated.achievedAcr shouldBe null
                authenticated.factorTypes shouldBe null
            }

            then("the address's send budget starts over") {
                verify { sendLimit.received("max@example.com") }
            }
        }

        `when`("submitting a wrong code") {
            val outcome = handler.patch(toolSessionId, "000000")

            then("it fails against the resolved account, so the orchestrator charges that account") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(Text("E-Mail oder Code ungueltig"), attempted = Attempted.Account(AccountId(42L)))
            }
        }
    }

    given("an unresolved address with a pending code") {
        val toolSessionId = ToolSessionId(UUID.randomUUID())
        val issued = emailCodeGenerator.issue()
        sessions.save(toolSessionId, AuthEmailLookupToolSession(accountId = null, issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt))

        `when`("submitting even the issued code") {
            val outcome = handler.patch(toolSessionId, issued.plainCode)

            then("it fails with the same wording and names no account to charge") {
                outcome shouldBe ToolOutcome.Failed.AccountLookupAuth(Text("E-Mail oder Code ungueltig"), attempted = null)
            }
        }
    }
})
