package com.example.identity.tools.auth_sms.internal.authsmslookup
import com.example.identity.contract.tool_api.Attempted
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.SmsSendBudget
import com.example.identity.contract.tool_api.directory.AccountDirectory

import com.example.identity.tools.auth_sms.AuthSmsLookupDescriptor
import com.example.identity.tools.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * toolId=auth-sms-lookup: login without a known account (docs/04-orchestrierung.md). Proves
 * possession of an enrolled phone number. The controller resolves the email and passes the result
 * into [submitEmail]. The tan-vs-state decision lives in [AuthSmsLookupFlow].
 */
@Component
class AuthSmsLookupToolHandler(
    private val descriptor: AuthSmsLookupDescriptor,
    private val toolDataRepository: AuthSmsLookupToolSessionRepository,
    private val enrollmentRepository: AuthSmsEnrollmentRepository,
    private val tanGenerator: TanGenerator,
    private val smsGateway: SmsGateway,
    private val sendBudget: SmsSendBudget,
    private val accountDirectory: AccountDirectory,
    private val clock: Clock
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        toolDataRepository.save(AuthSmsLookupToolSession(toolSessionId = toolSessionId, createdAt = clock.instant()))
        return outcomeFor(AuthSmsLookupState.AwaitingEmail)
    }

    /**
     * [accountId]/[enrollmentRef] are null for an unknown email, no active sms method, or a
     * locked account. That is handled like a wrong TAN, so the response never reveals whether
     * the email exists (docs/04-orchestrierung.md). Without a resolution nothing is sent. An
     * exhausted [SmsSendBudget] joins that branch, for the same reason (ADR-44).
     */
    @Transactional
    fun submitEmail(toolSessionId: UUID, accountId: Long?, enrollmentRef: EnrollmentRef?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms-lookup tool session: $toolSessionId" }

        val enrollment = enrollmentRef
            ?.takeIf { it.type == SMS_ENROLLMENT_TYPE }
            ?.id?.toLongOrNull()
            ?.let { enrollmentRepository.findByIdOrNull(it) }
            ?.takeIf { sendBudget.trySend(it.phoneNumber.orEmpty()) }
        val resolvedAccountId = accountId.takeIf { enrollment != null }

        val issued = tanGenerator.issue()
        data.accountId = resolvedAccountId
        data.issuedTanHash = issued.hash
        data.tanExpiresAt = issued.expiresAt
        toolDataRepository.save(data)

        val state = AuthSmsLookupState.AwaitingTan(resolvedAccountId, issued.hash, issued.expiresAt)
        val (step, fields) = state.describe()
        // Only actually "send" (and reveal a demoTan for) an SMS when the email really resolved
        // to an account with an active sms method - otherwise there is nothing to send to.
        return if (enrollment != null) {
            smsGateway.sendTan(enrollment.phoneNumber.orEmpty(), issued.plainTan)
            ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plainTan))
        } else {
            ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = state.demo)
        }
    }

    /** Called directly by AuthSmsLookupToolController (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, tan: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms-lookup tool session: $toolSessionId" }

        return when (val decision = AuthSmsLookupFlow.decideTan(data.toState(), tan, tanGenerator)) {
            is AuthSmsLookupDecision.Unchanged -> outcomeFor(decision.state)

            is AuthSmsLookupDecision.Complete -> {
                accountDirectory.activeEnrollment(decision.accountId, descriptor.method)
                    ?.id?.toLongOrNull()
                    ?.let { enrollmentRepository.findByIdOrNull(it) }
                    ?.let { sendBudget.received(it.phoneNumber.orEmpty()) }
                ToolOutcome.Completed.Authenticated(
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes,
                    subject = Subject.Account(decision.accountId)
                )
            }

            is AuthSmsLookupDecision.WrongTan ->
                // accountId names the throttle subject for the orchestrator; it is null exactly
                // when nothing resolved, so there is nothing to count either.
                ToolOutcome.Failed.LookupAuth(Text("E-Mail oder TAN ungueltig"), attempted = decision.accountId?.let(Attempted::Account))
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms-lookup tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: AuthSmsLookupState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = state.demo)
    }

    private fun AuthSmsLookupToolSession.toState(): AuthSmsLookupState = AuthSmsLookupState.of(
        toolSessionId = checkNotNull(toolSessionId),
        accountId = accountId,
        issuedTanHash = issuedTanHash,
        tanExpiresAt = tanExpiresAt
    )
}
