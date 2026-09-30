package com.example.identity.tools.auth_qr.internal.authqrlookup

import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_qr.AuthQrLookupDescriptor
import com.example.identity.tools.auth_qr.api.v1.QrPairingStep
import com.example.identity.tools.auth_qr.internal.QrLoginBrowserSide
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/**
 * toolId=auth-qr-lookup: the WEB channel does not know the account yet. The account approving via
 * `confirm-qr-login` reveals it, a passwordless "log in with your phone" (docs/04-orchestrierung.md).
 */
@Component
class AuthQrLookupToolHandler(
    private val descriptor: AuthQrLookupDescriptor,
    private val toolDataRepository: AuthQrLookupToolSessionRepository,
    private val browserSide: QrLoginBrowserSide,
    private val clock: Clock
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        val pairingCode = browserSide.open(expectedAccountId = null)
        toolDataRepository.save(AuthQrLookupToolSession(toolSessionId = toolSessionId, pairingCode = pairingCode, createdAt = clock.instant()))
        return waitingFor(pairingCode)
    }

    /**
     * Every browser PATCH: an empty poll while the app has not decided, then the confirmation code
     * the app shows ([QrLoginBrowserSide]). The account is whichever confirmed.
     */
    @Transactional
    fun patch(toolSessionId: UUID, confirmationCode: String?): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-qr-lookup tool session: $toolSessionId" }
        val pairingCode = checkNotNull(data.pairingCode)
        return when (val state = browserSide.advance(pairingCode, confirmationCode)) {
            QrLoginBrowserSide.State.WaitingForApp -> waitingFor(pairingCode)
            QrLoginBrowserSide.State.EnterCode -> ENTER_CODE
            is QrLoginBrowserSide.State.Confirmed -> ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method),
                achievedAcr = descriptor.maxAcr,
                factorTypes = descriptor.factorTypes,
                subject = Subject.Account(state.accountId)
            )
            // What is guessed here is the confirmation code, bounded by the pairing's own attempt
            // budget - not a secret of the account that approved.
            is QrLoginBrowserSide.State.Failed -> ToolOutcome.Failed.AccountLookupAuth(state.reason, attempted = null)
        }
    }

    /**
     * Rebuilds the current step without deciding anything. A declined or expired request reads as
     * `closed`: only the next PATCH reports that outcome to the journey (docs/05-api.md).
     */
    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        val data = checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) { "Unknown auth-qr-lookup tool session: $toolSessionId" }
        val pairingCode = checkNotNull(data.pairingCode)
        return when (browserSide.advance(pairingCode, null)) {
            QrLoginBrowserSide.State.WaitingForApp -> waitingFor(pairingCode)
            QrLoginBrowserSide.State.EnterCode -> ENTER_CODE
            else -> CLOSED
        }
    }

    private fun waitingFor(pairingCode: String) =
        ToolOutcome.InProgress(nextStep = "waitForApp", stepData = QrPairingStep(pairingCode))

    private companion object {
        val ENTER_CODE = ToolOutcome.InProgress(nextStep = "enterCode", stepData = MissingFields(listOf("confirmationCode")))
        val CLOSED = ToolOutcome.InProgress(nextStep = "closed")
    }
}
