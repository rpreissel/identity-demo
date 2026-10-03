package com.example.identity.tools.auth_qr.internal.authqr

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_qr.api.v1.QrPairingStep
import com.example.identity.tools.auth_qr.internal.QrLoginBrowserSide
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * toolId=auth-qr: the account is already known via the channel (step-up or re-auth), and the
 * approval must match [QrLoginRequest.expectedAccountId] (docs/04-orchestrierung.md).
 */
@Component
class AuthQrToolHandler(
    private val sessions: ToolSessionData,
    private val browserSide: QrLoginBrowserSide,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId, accountId: AccountId): ToolOutcome {
        val pairingCode = browserSide.open(expectedAccountId = accountId)
        sessions.save(toolSessionId, AuthQrToolSession(pairingCode = pairingCode))
        return waitingFor(pairingCode)
    }

    /**
     * Every browser PATCH: an empty poll while the app has not decided, then the confirmation code
     * the app shows ([QrLoginBrowserSide]).
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, confirmationCode: String?): ToolOutcome {
        val data = sessions.require<AuthQrToolSession>(toolSessionId)
        val pairingCode = checkNotNull(data.pairingCode)
        return when (val state = browserSide.advance(pairingCode, confirmationCode)) {
            QrLoginBrowserSide.State.WaitingForApp -> waitingFor(pairingCode)
            QrLoginBrowserSide.State.EnterCode -> ENTER_CODE
            is QrLoginBrowserSide.State.Confirmed ->
                if (state.accountId == state.expectedAccountId) {
                    ToolOutcome.Completed.Authenticated()
                } else {
                    // A different account confirmed than the one this WEB session already knows -
                    // never silently take over (same reasoning as Action.RecordIdentification's account check).
                    ToolOutcome.Failed.KnownAccountAuth(Text("Bestätigung passt nicht zu diesem Konto"))
                }
            is QrLoginBrowserSide.State.Failed -> ToolOutcome.Failed.KnownAccountAuth(state.reason)
        }
    }

    /**
     * Rebuilds the current step without deciding anything. A declined or expired request reads as
     * `closed`: only the next PATCH reports that outcome to the journey (docs/05-api.md).
     */
    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        val data = sessions.require<AuthQrToolSession>(toolSessionId)
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
