package com.example.identity.tools.auth_email.internal.authemaillookup
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.Lockouts
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.simulation.mail.MailServer
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator
import com.example.identity.tools.auth_email.internal.EmailSendLimit

import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.directory.resolveAccountByEmail
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=auth-email-lookup: login without a known account (docs/04-orchestrierung.md). Proves
 * possession of the account's confirmed email address; [submitEmail] resolves the account from the
 * address through the anchor ports. The code-vs-state decision lives in [AuthEmailLookupFlow].
 */
@Component
class AuthEmailLookupToolHandler(
    private val sessions: ToolSessionData,
    private val accountDirectory: AccountDirectory,
    private val emailCodeGenerator: EmailCodeGenerator,
    private val mailServer: MailServer,
    private val sendLimit: EmailSendLimit,
    private val lockouts: Lockouts,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.save(toolSessionId, AuthEmailLookupToolSession())
        return outcomeFor(AuthEmailLookupState.AwaitingEmail)
    }

    /**
     * Enumeration protection (docs/04-orchestrierung.md): an unknown or unconfirmed address looks
     * like a known one. Both branches issue a code and return the same step; only the send is
     * skipped, and the null accountId makes the code check fail. [locked] and an exhausted
     * [EmailSendLimit] join that branch, so neither reveals an account (ADR-44).
     */
    @Transactional
    fun submitEmail(toolSessionId: ToolSessionId, email: String, locked: Boolean): ToolOutcome {
        val candidateAccountId = accountDirectory.resolveAccountByEmail(email).takeUnless { locked }
        val confirmedEmail = candidateAccountId
            ?.let { accountDirectory.anchorValue(it, AttributeType.EMAIL) }
            ?.takeIf { sendLimit.trySend(it) }
        val resolvedAccountId = candidateAccountId.takeIf { confirmedEmail != null }

        val issued = emailCodeGenerator.issue()
        sessions.save(
            toolSessionId,
            AuthEmailLookupToolSession(accountId = resolvedAccountId, issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt),
        )

        val state = AuthEmailLookupState.AwaitingCode(resolvedAccountId, issued.hash, issued.expiresAt)
        val (step, fields) = state.describe()
        // Only actually "send" (and reveal a demo code for) an email when it really resolved to
        // a confirmed account address - otherwise there is nothing to send to.
        return if (confirmedEmail != null) {
            mailServer.sendCode(confirmedEmail, issued.plain)
            ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plain))
        } else {
            ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = state.demo)
        }
    }

    /** Called directly by AuthEmailLookupToolController (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, code: String?): ToolOutcome {
        val data = sessions.require<AuthEmailLookupToolSession>(toolSessionId)
        // A code is checked only after its attempt is booked. A locked account looks like a wrong code.
        if (code != null && data.accountId != null && !lockouts.admitAttempt(data.accountId)) {
            return ToolOutcome.Failed.AccountLookupAuth(Text("E-Mail oder Code ungueltig"), attempted = null)
        }

        return when (val decision = AuthEmailLookupFlow.decideCode(data.toState(toolSessionId), code, emailCodeGenerator)) {
            is AuthEmailLookupDecision.Unchanged -> outcomeFor(decision.state)

            is AuthEmailLookupDecision.Complete -> {
                accountDirectory.anchorValue(decision.accountId, AttributeType.EMAIL)?.let { sendLimit.received(it) }
                ToolOutcome.Completed.Authenticated(
                    subject = Subject.Account(decision.accountId)
                )
            }

            is AuthEmailLookupDecision.WrongCode ->
                // accountId names the rate limit subject for the orchestrator; it is null exactly
                // when nothing resolved, so there is nothing to count either.
                ToolOutcome.Failed.AccountLookupAuth(Text("E-Mail oder Code ungueltig"), attempted = decision.accountId?.let(Attempted::Account))
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome.InProgress {
        return outcomeFor(sessions.require<AuthEmailLookupToolSession>(toolSessionId).toState(toolSessionId))
    }

    private fun outcomeFor(state: AuthEmailLookupState): ToolOutcome.InProgress {
        return state.describe().inProgress(state.demo)
    }

    private fun AuthEmailLookupToolSession.toState(toolSessionId: ToolSessionId): AuthEmailLookupState = AuthEmailLookupState.of(
        toolSessionId = toolSessionId,
        accountId = accountId,
        issuedCodeHash = issuedCodeHash,
        codeExpiresAt = codeExpiresAt
    )
}
