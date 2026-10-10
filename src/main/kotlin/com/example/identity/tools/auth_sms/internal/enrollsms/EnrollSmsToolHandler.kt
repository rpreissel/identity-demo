package com.example.identity.tools.auth_sms.internal.enrollsms
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.MasterKeyId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_sms.PHONE_NUMBER
import com.example.identity.contract.tool_api.InvalidInputException
import com.example.identity.simulation.sms.SmsGateway
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_sms.internal.SmsNumbers
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.SmsSendLimit

import com.example.identity.tools.auth_sms.SmsModule
import com.example.identity.tools.auth_sms.internal.SMS_ENROLLMENT_TYPE
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.TooManyRequestsException
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.ToolRole
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * toolId=enroll-sms (docs/verfahren/sms.md): registers a new phone number as a 2nd factor. This
 * class translates [EnrollSmsFlow]'s decisions into writes and the outward [ToolOutcome]. Serves
 * both versions (ADR-51): version 2 asks for the consent with the number, version 1 cannot show
 * it and goes without; its runs stand in the change log as `enroll-sms@1`.
 */
@Component
class EnrollSmsToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: AuthSmsEnrollmentRepository,
    private val tanGenerator: TanGenerator,
    private val smsGateway: SmsGateway,
    private val sendLimit: SmsSendLimit,
    private val numbers: SmsNumbers,
    private val clock: Clock
) {

    /** Called directly by the controllers. [replaces]: the account already has a number. */
    @Transactional
    fun start(toolSessionId: ToolSessionId, version: Int, replaces: Boolean = false): ToolOutcome {
        val data = EnrollSmsToolSession(replaces = replaces)
        sessions.save(toolSessionId, data)
        return outcomeFor(EnrollSmsState.AwaitingPhoneNumber, data, version)
    }

    /**
     * Every [EnrollSmsDecision.SendTan] passes [SmsSendLimit]: resubmitting a number is never a
     * wrong guess, so without it anyone knowing a number could use this tool as an SMS bomb. The
     * caller chose the number, so an exhausted budget may say so: a 429, not a failed attempt of the
     * journey, since nothing was guessed.
     */
    @Transactional
    /** [masterKey] names the key the confirmed number is sealed under (ADR-55); asked only on completion. */
    fun patch(toolSessionId: ToolSessionId, version: Int, phoneNumber: String?, tan: String?, consent: Boolean? = null, masterKey: () -> MasterKeyId): ToolOutcome {
        val data = sessions.require<EnrollSmsToolSession>(toolSessionId)
        val input = EnrollSmsInput(phoneNumber, tan, consent)

        return when (val decision = EnrollSmsFlow.decide(data.toState(toolSessionId), input, tanGenerator, needsConsent(data, version))) {
            is EnrollSmsDecision.InvalidPhoneNumber -> throw InvalidInputException(Text("Bitte eine Mobilnummer mit Ländervorwahl aus der EU oder dem EWR angeben, z. B. +49 170 1234567"))

            is EnrollSmsDecision.WrongTan -> ToolOutcome.Failed.NothingGuessed(Text("TAN ungueltig oder abgelaufen"))

            is EnrollSmsDecision.Unchanged -> outcomeFor(decision.state, data, version)

            is EnrollSmsDecision.ConsentMissing -> outcomeFor(decision.state, data, version)

            is EnrollSmsDecision.SendTan -> if (!sendLimit.trySend(decision.phoneNumber)) {
                throw TooManyRequestsException(Text("Zu viele Codes angefordert. Bitte versuchen Sie es in einigen Minuten erneut."))
            } else {
                val issued = tanGenerator.issue()
                sessions.save(
                    toolSessionId,
                    data.copy(
                        phoneNumber = decision.phoneNumber, issuedTanHash = issued.hash, tanExpiresAt = issued.expiresAt,
                        consented = data.consented || consent == true,
                    ),
                )
                smsGateway.sendTan(decision.phoneNumber, issued.plain)

                val state = EnrollSmsState.AwaitingTan(decision.phoneNumber, issued.hash, issued.expiresAt)
                val (step, fields) = state.describe(data.replaces, needsConsent = false)
                // demoTan: this is a demo, not a real SMS gateway - showing it in the UI means
                // testers don't need server-log access (docs/verfahren/sms.md).
                ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plain))
            }

            is EnrollSmsDecision.Complete -> {
                sendLimit.received(decision.phoneNumber)
                val enrollment = enrollmentRepository.save(numbers.newEnrollment(decision.phoneNumber, masterKey(), createdAt = clock.instant()))
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
    fun read(toolSessionId: ToolSessionId, version: Int): ToolOutcome.InProgress {
        val data = sessions.require<EnrollSmsToolSession>(toolSessionId)
        return outcomeFor(data.toState(toolSessionId), data, version)
    }

    private fun outcomeFor(state: EnrollSmsState, data: EnrollSmsToolSession, version: Int): ToolOutcome.InProgress {
        val (step, fields) = state.describe(data.replaces, needsConsent(data, version))
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    /** Version 2 asks for the consent once per run; version 1 never (entfällt mit v1). */
    private fun needsConsent(data: EnrollSmsToolSession, version: Int): Boolean = version >= 2 && !data.consented

    private fun EnrollSmsToolSession.toState(toolSessionId: ToolSessionId): EnrollSmsState = EnrollSmsState.of(
        toolSessionId = toolSessionId,
        phoneNumber = phoneNumber,
        issuedTanHash = issuedTanHash,
        tanExpiresAt = tanExpiresAt
    )
}
