package com.example.identity.tools.auth_qr.internal.authqrlookup

import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_qr.api.v1.QrPairingStep
import com.example.identity.tools.auth_qr.internal.QrLoginBrowserSide
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=auth-qr-lookup: the WEB channel does not know the account yet. The account approving via
 * `approve-qr` reveals it, a passwordless "log in with your phone" (docs/04-orchestrierung.md).
 */
@Component
class AuthQrLookupToolHandler(
    private val sessions: ToolSessionData,
    private val browserSide: QrLoginBrowserSide,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        val pairingCode = browserSide.open(expectedAccountId = null)
        sessions.save(toolSessionId, AuthQrLookupToolSession(pairingCode = pairingCode))
        return waitingFor(pairingCode)
    }

    /**
     * Every browser PATCH: an empty poll while the app has not decided, then the confirmation code
     * the app shows ([QrLoginBrowserSide]). The account is whichever confirmed.
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, confirmationCode: String?): ToolOutcome {
        val data = sessions.require<AuthQrLookupToolSession>(toolSessionId)
        val pairingCode = checkNotNull(data.pairingCode)
        return when (val state = browserSide.advance(pairingCode, confirmationCode)) {
            QrLoginBrowserSide.State.WaitingForApp -> waitingFor(pairingCode)
            QrLoginBrowserSide.State.EnterCode -> ENTER_CODE
            is QrLoginBrowserSide.State.Confirmed -> ToolOutcome.Completed.Authenticated(
                subject = Subject.Account(state.accountId)
            )
            // What is guessed here is the confirmation code, bounded by the pairing's own attempt
            // budget - not a secret of the account that approved.
            is QrLoginBrowserSide.State.Failed -> ToolOutcome.Failed.AccountLookupAuth(state.reason, attempted = null)
        }
    }

    /**
     * Rebuilds the current step without deciding anything. A declined or expired request reads as
     * `closed`: only the next PATCH reports that outcome to the journey (docs/verfahren/qr.md).
     */
    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome.InProgress {
        val data = sessions.require<AuthQrLookupToolSession>(toolSessionId)
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
