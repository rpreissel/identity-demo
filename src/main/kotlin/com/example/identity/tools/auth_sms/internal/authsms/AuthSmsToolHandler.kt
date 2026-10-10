package com.example.identity.tools.auth_sms.internal.authsms
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.credentials.requireEnrollment
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.SmsSendLimit
import com.example.identity.contract.tool_api.TooManyRequestsException

import com.example.identity.tools.auth_sms.internal.SMS_ENROLLMENT_TYPE
import com.example.identity.tools.auth_sms.internal.SmsNumbers
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=auth-sms (docs/verfahren/sms.md). [start]'s [enrollmentRef] is resolved by the
 * controller, since this module never reads `account`. The tan-vs-state decision lives in [AuthSmsFlow].
 */
@Component
class AuthSmsToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: AuthSmsEnrollmentRepository,
    private val tanGenerator: TanGenerator,
    private val smsGateway: SmsGateway,
    private val sendLimit: SmsSendLimit,
    private val numbers: SmsNumbers,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId, enrollmentRef: EnrollmentRef): ToolOutcome {
        val enrollment = enrollmentRepository.requireEnrollment(enrollmentRef, SMS_ENROLLMENT_TYPE)

        val phoneNumber = numbers.phoneNumberOf(enrollment)
        // The channel already knows the account, so saying "too many" reveals nothing.
        if (!sendLimit.trySend(phoneNumber)) {
            throw TooManyRequestsException(Text("Zu viele Codes angefordert. Bitte versuchen Sie es in einigen Minuten erneut."))
        }
        val issued = tanGenerator.issue()
        sessions.save(
            toolSessionId,
            AuthSmsToolSession(enrollmentRefId = enrollmentRef.id, issuedTanHash = issued.hash, tanExpiresAt = issued.expiresAt),
        )
        smsGateway.sendTan(phoneNumber, issued.plain)

        // demoTan: this is a demo, not a real SMS gateway - showing it in the UI means testers
        // don't need server-log access (docs/verfahren/sms.md).
        val (step, fields) = AuthSmsState(issued.hash, issued.expiresAt).describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plain))
    }

    /** Called directly by AuthSmsToolController, not generically dispatched (docs/08-projektrahmen.md A11). */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, tan: String?): ToolOutcome {
        val data = sessions.require<AuthSmsToolSession>(toolSessionId)
        val state = data.toState(toolSessionId)

        return when (AuthSmsFlow.decide(state, AuthSmsInput(tan), tanGenerator)) {
            AuthSmsDecision.Unchanged -> outcomeFor(state)
            AuthSmsDecision.WrongTan -> ToolOutcome.Failed.KnownAccountAuth(Text("TAN ungueltig oder abgelaufen"))
            AuthSmsDecision.Complete -> {
                val enrollment = enrollmentRepository.requireEnrollment(data.enrollmentRefId, toolSessionId)
                sendLimit.received(numbers.phoneNumberOf(enrollment))
                ToolOutcome.Completed.Authenticated()
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome.InProgress {
        return outcomeFor(sessions.require<AuthSmsToolSession>(toolSessionId).toState(toolSessionId))
    }

    private fun outcomeFor(state: AuthSmsState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun AuthSmsToolSession.toState(toolSessionId: ToolSessionId): AuthSmsState = AuthSmsState.of(
        toolSessionId = toolSessionId,
        issuedTanHash = issuedTanHash,
        tanExpiresAt = tanExpiresAt
    )
}
