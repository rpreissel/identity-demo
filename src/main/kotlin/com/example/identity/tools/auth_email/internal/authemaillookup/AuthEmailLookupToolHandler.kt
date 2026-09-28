package com.example.identity.tools.auth_email.internal.authemaillookup
import com.example.identity.simulation.mail.MailServer
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator
import com.example.identity.tools.auth_email.internal.EmailSendBudget

import com.example.identity.tools.auth_email.AuthEmailLookupDescriptor
import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.directory.resolveAccountByEmail
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * toolId=auth-email-lookup: login without a known account (docs/04-orchestrierung.md). Proves
 * possession of the account's confirmed email address; [submitEmail] resolves the account from the
 * address through the anchor ports. The code-vs-state decision lives in [AuthEmailLookupFlow].
 */
@Component
class AuthEmailLookupToolHandler(
    private val descriptor: AuthEmailLookupDescriptor,
    private val toolDataRepository: AuthEmailLookupToolSessionRepository,
    private val accountDirectory: AccountDirectory,
    private val emailCodeGenerator: EmailCodeGenerator,
    private val mailServer: MailServer,
    private val sendBudget: EmailSendBudget,
    private val clock: Clock
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(AuthEmailLookupToolSession(toolSessionId = toolSessionId, createdAt = clock.instant()))
        return outcomeFor(AuthEmailLookupState.AwaitingEmail)
    }

    /**
     * Enumeration protection (docs/04-orchestrierung.md): an unknown or unconfirmed address looks
     * like a known one. Both branches issue a code and return the same step; only the send is
     * skipped, and the null accountId makes the code check fail. [locked] and an exhausted
     * [EmailSendBudget] join that branch, so neither reveals an account (ADR-44).
     */
    @Transactional
    fun submitEmail(toolSessionId: UUID, email: String, locked: Boolean): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email-lookup tool session: $toolSessionId" }

        val candidateAccountId = accountDirectory.resolveAccountByEmail(email).takeUnless { locked }
        val confirmedEmail = candidateAccountId
            ?.let { accountDirectory.anchorValue(it, AttributeType.EMAIL) }
            ?.takeIf { sendBudget.trySend(it) }
        val resolvedAccountId = candidateAccountId.takeIf { confirmedEmail != null }

        val issued = emailCodeGenerator.issue()
        data.accountId = resolvedAccountId
        data.issuedCodeHash = issued.hash
        data.codeExpiresAt = issued.expiresAt
        toolDataRepository.save(data)

        val state = AuthEmailLookupState.AwaitingCode(resolvedAccountId, issued.hash, issued.expiresAt)
        val (step, fields) = state.describe()
        // Only actually "send" (and reveal a demo code for) an email when it really resolved to
        // a confirmed account address - otherwise there is nothing to send to.
        return if (confirmedEmail != null) {
            mailServer.sendCode(confirmedEmail, issued.plainCode)
            ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plainCode))
        } else {
            ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = state.demo)
        }
    }

    /** Called directly by AuthEmailLookupToolController (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, code: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email-lookup tool session: $toolSessionId" }

        return when (val decision = AuthEmailLookupFlow.decideCode(data.toState(), code, emailCodeGenerator)) {
            is AuthEmailLookupDecision.Unchanged -> outcomeFor(decision.state)

            is AuthEmailLookupDecision.Complete -> {
                accountDirectory.anchorValue(decision.accountId, AttributeType.EMAIL)?.let { sendBudget.received(it) }
                ToolOutcome.Completed.Authenticated(
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes,
                    accountId = decision.accountId
                )
            }

            is AuthEmailLookupDecision.WrongCode ->
                // accountId names the throttle subject for the orchestrator; it is null exactly
                // when nothing resolved, so there is nothing to count either.
                ToolOutcome.Failed.LookupAuth(Text("E-Mail oder Code ungueltig"), attemptedAccountId = decision.accountId)
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-email-lookup tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: AuthEmailLookupState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = state.demo)
    }

    private fun AuthEmailLookupToolSession.toState(): AuthEmailLookupState = AuthEmailLookupState.of(
        toolSessionId = checkNotNull(toolSessionId),
        accountId = accountId,
        issuedCodeHash = issuedCodeHash,
        codeExpiresAt = codeExpiresAt
    )
}
