package com.example.identity.tools.auth_sms.internal.enrollsms
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_sms.PHONE_NUMBER
import com.example.identity.contract.tool_api.InvalidInputException
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollment
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.SmsSendLimit

import com.example.identity.tools.auth_sms.SmsModule
import com.example.identity.tools.auth_sms.internal.SMS_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.TooManyRequestsException
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.ToolRole
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * toolId=enroll-sms (docs/06-ablaeufe.md #4): registers a new phone number as a 2nd factor. This
 * class translates [EnrollSmsFlow]'s decisions into writes and the outward [ToolOutcome].
 */
@Component
class EnrollSmsToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: AuthSmsEnrollmentRepository,
    private val tanGenerator: TanGenerator,
    private val smsGateway: SmsGateway,
    private val sendLimit: SmsSendLimit,
    private val clock: Clock
) {

    /** Called directly by EnrollSmsToolController. [replaces]: the account already has a number. */
    @Transactional
    fun start(toolSessionId: ToolSessionId, replaces: Boolean = false): ToolOutcome {
        sessions.save(toolSessionId, EnrollSmsToolSession(replaces = replaces))
        return outcomeFor(EnrollSmsState.AwaitingPhoneNumber, replaces)
    }

    /**
     * Every [EnrollSmsDecision.SendTan] passes [SmsSendLimit]: resubmitting a number is never a
     * wrong guess, so without it anyone knowing a number could use this tool as an SMS bomb. The
     * caller chose the number, so an exhausted budget may say so: a 429, not a failed attempt of the
     * journey, since nothing was guessed.
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, phoneNumber: String?, tan: String?): ToolOutcome {
        val data = sessions.require<EnrollSmsToolSession>(toolSessionId)

        return when (val decision = EnrollSmsFlow.decide(data.toState(toolSessionId), EnrollSmsInput(phoneNumber, tan), tanGenerator)) {
            is EnrollSmsDecision.InvalidPhoneNumber -> throw InvalidInputException(Text("Bitte eine Mobilnummer mit Ländervorwahl aus der EU oder dem EWR angeben, z. B. +49 170 1234567"))

            is EnrollSmsDecision.WrongTan -> ToolOutcome.Failed.NothingGuessed(Text("TAN ungueltig oder abgelaufen"))

            is EnrollSmsDecision.Unchanged -> outcomeFor(decision.state, data.replaces)

            is EnrollSmsDecision.SendTan -> if (!sendLimit.trySend(decision.phoneNumber)) {
                throw TooManyRequestsException(Text("Zu viele Codes angefordert. Bitte versuchen Sie es in einigen Minuten erneut."))
            } else {
                val issued = tanGenerator.issue()
                sessions.save(
                    toolSessionId,
                    data.copy(phoneNumber = decision.phoneNumber, issuedTanHash = issued.hash, tanExpiresAt = issued.expiresAt),
                )
                smsGateway.sendTan(decision.phoneNumber, issued.plainTan)

                val state = EnrollSmsState.AwaitingTan(decision.phoneNumber, issued.hash, issued.expiresAt)
                val (step, fields) = state.describe(data.replaces)
                // demoTan: this is a demo, not a real SMS gateway - showing it in the UI means
                // testers don't need server-log access (docs/06-ablaeufe.md #4).
                ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plainTan))
            }

            is EnrollSmsDecision.Complete -> {
                sendLimit.received(decision.phoneNumber)
                val enrollment = enrollmentRepository.save(AuthSmsEnrollment(decision.phoneNumber, createdAt = clock.instant()))
                ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef(type = SMS_ENROLLMENT_TYPE, id = enrollment.id.toString()),
                    claims = listOf(
                        Claim(
                            attributeType = PHONE_NUMBER,
                            value = decision.phoneNumber,
                            source = SmsModule.source(ToolRole.ENROLLMENT),
                            establishedAcr = SmsModule.maxAcr
                        )
                    )
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        val data = sessions.require<EnrollSmsToolSession>(toolSessionId)
        return outcomeFor(data.toState(toolSessionId), data.replaces)
    }

    private fun outcomeFor(state: EnrollSmsState, replaces: Boolean): ToolOutcome.InProgress {
        val (step, fields) = state.describe(replaces)
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun EnrollSmsToolSession.toState(toolSessionId: ToolSessionId): EnrollSmsState = EnrollSmsState.of(
        toolSessionId = toolSessionId,
        phoneNumber = phoneNumber,
        issuedTanHash = issuedTanHash,
        tanExpiresAt = tanExpiresAt
    )
}
