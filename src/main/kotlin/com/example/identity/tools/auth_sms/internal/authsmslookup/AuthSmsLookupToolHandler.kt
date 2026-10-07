package com.example.identity.tools.auth_sms.internal.authsmslookup
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.SmsSendLimit
import com.example.identity.contract.tool_api.directory.AccountDirectory

import com.example.identity.tools.auth_sms.SmsModule
import com.example.identity.tools.auth_sms.internal.SMS_ENROLLMENT_TYPE
import com.example.identity.tools.auth_sms.internal.SmsNumbers
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=auth-sms-lookup: login without a known account (docs/04-orchestrierung.md). Proves
 * possession of an enrolled phone number. The controller resolves the email and passes the result
 * into [submitEmail]. The tan-vs-state decision lives in [AuthSmsLookupFlow].
 */
@Component
class AuthSmsLookupToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: AuthSmsEnrollmentRepository,
    private val tanGenerator: TanGenerator,
    private val smsGateway: SmsGateway,
    private val sendLimit: SmsSendLimit,
    private val accountDirectory: AccountDirectory,
    private val numbers: SmsNumbers,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.save(toolSessionId, AuthSmsLookupToolSession())
        return outcomeFor(AuthSmsLookupState.AwaitingEmail)
    }

    /**
     * [accountId]/[enrollmentRef] are null for an unknown email, no active sms method, or a
     * locked account. That is handled like a wrong TAN, so the response never reveals whether
     * the email exists (docs/04-orchestrierung.md). Without a resolution nothing is sent. An
     * exhausted [SmsSendLimit] joins that branch, for the same reason (ADR-44).
     */
    @Transactional
    fun submitEmail(toolSessionId: ToolSessionId, accountId: AccountId?, enrollmentRef: EnrollmentRef?): ToolOutcome {
        val enrollment = enrollmentRef
            ?.takeIf { it.type == SMS_ENROLLMENT_TYPE }
            ?.id?.toLongOrNull()
            ?.let { enrollmentRepository.findByIdOrNull(it) }
            ?.let { numbers.phoneNumberOf(it) }
            ?.takeIf { sendLimit.trySend(it) }
        val resolvedAccountId = accountId.takeIf { enrollment != null }

        val issued = tanGenerator.issue()
        sessions.save(
            toolSessionId,
            AuthSmsLookupToolSession(accountId = resolvedAccountId, issuedTanHash = issued.hash, tanExpiresAt = issued.expiresAt),
        )

        val state = AuthSmsLookupState.AwaitingTan(resolvedAccountId, issued.hash, issued.expiresAt)
        val (step, fields) = state.describe()
        // Only actually "send" (and reveal a demoTan for) an SMS when the email really resolved
        // to an account with an active sms method - otherwise there is nothing to send to.
        return if (enrollment != null) {
            smsGateway.sendTan(enrollment, issued.plainTan)
            ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plainTan))
        } else {
            ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = state.demo)
        }
    }

    /** Called directly by AuthSmsLookupToolController (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, tan: String?): ToolOutcome {
        val data = sessions.require<AuthSmsLookupToolSession>(toolSessionId)

        return when (val decision = AuthSmsLookupFlow.decideTan(data.toState(toolSessionId), tan, tanGenerator)) {
            is AuthSmsLookupDecision.Unchanged -> outcomeFor(decision.state)

            is AuthSmsLookupDecision.Complete -> {
                accountDirectory.activeEnrollment(decision.accountId, SmsModule.method)
                    ?.id?.toLongOrNull()
                    ?.let { enrollmentRepository.findByIdOrNull(it) }
                    ?.let { sendLimit.received(numbers.phoneNumberOf(it)) }
                ToolOutcome.Completed.Authenticated(
                    subject = Subject.Account(decision.accountId)
                )
            }

            is AuthSmsLookupDecision.WrongTan ->
                // accountId names the rate limit subject for the orchestrator; it is null exactly
                // when nothing resolved, so there is nothing to count either.
                ToolOutcome.Failed.AccountLookupAuth(Text("E-Mail oder TAN ungueltig"), attempted = decision.accountId?.let(Attempted::Account))
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        return outcomeFor(sessions.require<AuthSmsLookupToolSession>(toolSessionId).toState(toolSessionId))
    }

    private fun outcomeFor(state: AuthSmsLookupState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = state.demo)
    }

    private fun AuthSmsLookupToolSession.toState(toolSessionId: ToolSessionId): AuthSmsLookupState = AuthSmsLookupState.of(
        toolSessionId = toolSessionId,
        accountId = accountId,
        issuedTanHash = issuedTanHash,
        tanExpiresAt = tanExpiresAt
    )
}
