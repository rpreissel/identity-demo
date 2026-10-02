package com.example.identity.tools.auth_email.internal.confirmemail
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.tools.auth_email.EmailModule
import com.example.identity.contract.tool_api.InvalidInputException
import com.example.identity.simulation.mail.MailServer
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator
import com.example.identity.tools.auth_email.internal.EmailSendLimit

import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.TooManyRequestsException
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * toolId=confirm-email, role=ATTESTATION: proves control of an address with a code exchange like
 * enroll-sms (docs/06-ablaeufe.md #4). The confirmed value is the account's EMAIL anchor, asserted
 * as an EMAIL claim on `Completed.Attested`; no method instance or device binding is created
 * (docs/02-domaenenmodell.md #6). This class translates [ConfirmEmailFlow]'s decisions into writes.
 */
@Component
class ConfirmEmailToolHandler(
    private val toolDataRepository: ConfirmEmailToolSessionRepository,
    private val emailCodeGenerator: EmailCodeGenerator,
    private val mailServer: MailServer,
    private val sendLimit: EmailSendLimit,
    private val clock: Clock
) {

    /** Called directly by ConfirmEmailToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        toolDataRepository.save(ConfirmEmailToolSession(toolSessionId = toolSessionId, createdAt = clock.instant()))
        return outcomeFor(ConfirmEmailState.AwaitingEmail)
    }

    /**
     * Every [ConfirmEmailDecision.RequestCode] passes [EmailSendLimit]: resubmitting an address is
     * never a wrong guess, so without it anyone knowing an address could use this tool as a mail
     * bomb. The caller chose the address, so an exhausted budget may say so:
     * a 429, not a failed attempt of the journey, since nothing was guessed.
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, email: String?, code: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByToolSessionId(toolSessionId)) { "Unknown confirm-email tool session: $toolSessionId" }

        return when (val decision = ConfirmEmailFlow.decide(data.toState(), ConfirmEmailInput(email, code), emailCodeGenerator)) {
            is ConfirmEmailDecision.InvalidEmail -> throw InvalidInputException(Text("Ungueltige E-Mail-Adresse"))

            is ConfirmEmailDecision.WrongCode -> ToolOutcome.Failed.NothingGuessed(Text("Code ungueltig oder abgelaufen"))

            is ConfirmEmailDecision.Unchanged -> outcomeFor(decision.state)

            is ConfirmEmailDecision.RequestCode -> {
                // No "address already taken?" check: nothing is proven yet, only typed. Who the
                // address belongs to is resolved once the code comes back (ADR-20).
                if (!sendLimit.trySend(decision.email)) {
                    throw TooManyRequestsException(Text("Zu viele Codes angefordert. Bitte versuchen Sie es in einigen Minuten erneut."))
                } else {
                    val issued = emailCodeGenerator.issue()
                    data.email = decision.email
                    data.issuedCodeHash = issued.hash
                    data.codeExpiresAt = issued.expiresAt
                    toolDataRepository.save(data)
                    mailServer.sendCode(decision.email, issued.plainCode)

                    val state = ConfirmEmailState.AwaitingCode(decision.email, issued.hash, issued.expiresAt)
                    val (step, fields) = state.describe()
                    // demoTan: reuses the existing demo-value plumbing (docs/05-api.md #2's `demo`
                    // object) - this is a demo, not a real mail gateway, and a second field for
                    // "the other kind of demo code" would be unnecessary special-casing.
                    ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = mapOf("tan" to issued.plainCode))
                }
            }

            is ConfirmEmailDecision.Complete -> {
                sendLimit.received(decision.email)
                ToolOutcome.Completed.Attested(
                    claims = listOf(
                        // The code exchange itself is the proof. No enrollmentRef and no amr: this run
                        // established a fact about the account, it authenticated nobody.
                        Claim(AttributeType.EMAIL, decision.email, EmailModule.source(ToolRole.ATTESTATION), EmailModule.maxAcr)
                    )
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByToolSessionId(toolSessionId)) { "Unknown confirm-email tool session: $toolSessionId" }
        return outcomeFor(data.toState())
    }

    private fun outcomeFor(state: ConfirmEmailState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields, demo = state.demo)
    }

    private fun ConfirmEmailToolSession.toState(): ConfirmEmailState = ConfirmEmailState.of(
        toolSessionId = checkNotNull(toolSessionId),
        email = email,
        issuedCodeHash = issuedCodeHash,
        codeExpiresAt = codeExpiresAt
    )
}
