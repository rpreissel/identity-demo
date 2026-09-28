package com.example.identity.tools.auth_sms.internal.authsms
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.SmsSendBudget
import com.example.identity.contract.tool_api.TooManyRequestsException

import com.example.identity.tools.auth_sms.AuthSmsDescriptor
import com.example.identity.tools.auth_sms.SMS_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * toolId=auth-sms (docs/06-ablaeufe.md #3). [start]'s [enrollmentRef] is resolved by the
 * controller, since this module never reads `account`. The tan-vs-state decision lives in [AuthSmsFlow].
 */
@Component
class AuthSmsToolHandler(
    private val descriptor: AuthSmsDescriptor,
    private val toolDataRepository: AuthSmsToolSessionRepository,
    private val enrollmentRepository: AuthSmsEnrollmentRepository,
    private val tanGenerator: TanGenerator,
    private val smsGateway: SmsGateway,
    private val sendBudget: SmsSendBudget
) {

    @Transactional
    fun start(toolSessionId: UUID, enrollmentRef: EnrollmentRef): ToolOutcome {
        if (enrollmentRef.type != SMS_ENROLLMENT_TYPE) {
            throw UnresolvableReferenceException(Text("Unerwarteter Enrollment-Typ"), "type=${enrollmentRef.type}")
        }
        val enrollmentId = enrollmentRef.id.toLongOrNull()
            ?: throw UnresolvableReferenceException(Text("Ungueltige Enrollment-Referenz"), "id=${enrollmentRef.id}")
        val enrollment = enrollmentRepository.findByIdOrNull(enrollmentId)
            ?: throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "id=${enrollmentRef.id}")

        // The channel already knows the account, so saying "too many" reveals nothing.
        if (!sendBudget.trySend(enrollment.phoneNumber.orEmpty())) {
            throw TooManyRequestsException(Text("Zu viele Codes angefordert. Bitte versuchen Sie es in einigen Minuten erneut."))
        }
        val issued = tanGenerator.issue()
        toolDataRepository.save(
            AuthSmsToolSession(
                toolSessionId = toolSessionId,
                enrollmentRefId = enrollmentRef.id,
                issuedTanHash = issued.hash,
                tanExpiresAt = issued.expiresAt
            )
        )
        smsGateway.sendTan(enrollment.phoneNumber.orEmpty(), issued.plainTan)

        // demoTan: this is a demo, not a real SMS gateway - showing it in the UI means testers
        // don't need server-log access (docs/06-ablaeufe.md #3).
        val (step, fields) = AuthSmsState(issued.hash, issued.expiresAt).describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plainTan))
    }

    /** Called directly by AuthSmsToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: UUID, tan: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms tool session: $toolSessionId" }
        val state = data.toState()

        return when (AuthSmsFlow.decide(state, AuthSmsInput(tan), tanGenerator)) {
            AuthSmsDecision.Unchanged -> outcomeFor(state)
            AuthSmsDecision.WrongTan -> ToolOutcome.Failed.IdentifiedAuth(Text("TAN ungueltig oder abgelaufen"))
            AuthSmsDecision.Complete -> {
                // Gone during the tool session (removed on another channel): a right TAN for a
                // method that no longer exists proves nothing.
                val enrollment = data.enrollmentRefId?.toLongOrNull()?.let { enrollmentRepository.findByIdOrNull(it) }
                    ?: throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "toolSession=$toolSessionId")
                sendBudget.received(enrollment.phoneNumber.orEmpty())
                ToolOutcome.Completed.Authenticated(
                    amr = listOf(descriptor.method),
                    achievedAcr = descriptor.maxAcr,
                    factorTypes = descriptor.factorTypes
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-sms tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: AuthSmsState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun AuthSmsToolSession.toState(): AuthSmsState = AuthSmsState.of(
        toolSessionId = checkNotNull(toolSessionId),
        issuedTanHash = issuedTanHash,
        tanExpiresAt = tanExpiresAt
    )
}
