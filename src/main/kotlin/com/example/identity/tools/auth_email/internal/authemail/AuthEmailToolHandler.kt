package com.example.identity.tools.auth_email.internal.authemail
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.simulation.mail.MailServer
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator
import com.example.identity.tools.auth_email.internal.EmailSendLimit
import com.example.identity.contract.tool_api.TooManyRequestsException

import com.example.identity.contract.tool_api.directory.AccountDirectory
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=auth-email, device-linked case (docs/verfahren/email.md). No EnrollmentRef: the
 * confirmed address is the account's EMAIL anchor, so [start] reads it through `anchorValue`.
 * The code-vs-state decision lives in [AuthEmailFlow].
 */
@Component
class AuthEmailToolHandler(
    private val sessions: ToolSessionData,
    private val accountDirectory: AccountDirectory,
    private val emailCodeGenerator: EmailCodeGenerator,
    private val mailServer: MailServer,
    private val sendLimit: EmailSendLimit,
) {

    /**
     * Resolves the account's confirmed address and fails with [UnresolvableReferenceException]
     * (-> 422) like its siblings do for an unresolvable EnrollmentRef.
     */
    @Transactional
    fun start(toolSessionId: ToolSessionId, accountId: AccountId): ToolOutcome {
        // Returns the normalized confirmed address from the anchor projection, or null when
        // none was ever established for this account.
        val email = accountDirectory.anchorValue(accountId, AttributeType.EMAIL)
            ?: throw UnresolvableReferenceException(Text("Keine bestaetigte E-Mail-Adresse fuer diesen Account"))

        // The channel already knows the account, so saying "too many" reveals nothing.
        if (!sendLimit.trySend(email)) {
            throw TooManyRequestsException(Text("Zu viele Codes angefordert. Bitte versuchen Sie es in einigen Minuten erneut."))
        }
        val issued = emailCodeGenerator.issue()
        sessions.save(toolSessionId, AuthEmailToolSession(issuedCodeHash = issued.hash, codeExpiresAt = issued.expiresAt))
        mailServer.sendCode(email, issued.plainCode)

        val (step, fields) = AuthEmailState(issued.hash, issued.expiresAt).describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plainCode))
    }

    /**
     * Called directly by AuthEmailToolController (docs/08-projektrahmen.md A11). [accountId] is
     * the channel's account, whose address a correct code resets in [EmailSendLimit].
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, code: String?, accountId: AccountId?): ToolOutcome {
        val state = sessions.require<AuthEmailToolSession>(toolSessionId).toState(toolSessionId)

        return when (AuthEmailFlow.decide(state, AuthEmailInput(code), emailCodeGenerator)) {
            AuthEmailDecision.Unchanged -> outcomeFor(state)
            AuthEmailDecision.WrongCode -> ToolOutcome.Failed.KnownAccountAuth(Text("Code ungueltig oder abgelaufen"))
            AuthEmailDecision.Complete -> {
                accountId?.let { accountDirectory.anchorValue(it, AttributeType.EMAIL) }?.let { sendLimit.received(it) }
                ToolOutcome.Completed.Authenticated()
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        return outcomeFor(sessions.require<AuthEmailToolSession>(toolSessionId).toState(toolSessionId))
    }

    private fun outcomeFor(state: AuthEmailState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun AuthEmailToolSession.toState(toolSessionId: ToolSessionId): AuthEmailState = AuthEmailState.of(
        toolSessionId = toolSessionId,
        issuedCodeHash = issuedCodeHash,
        codeExpiresAt = codeExpiresAt
    )
}
