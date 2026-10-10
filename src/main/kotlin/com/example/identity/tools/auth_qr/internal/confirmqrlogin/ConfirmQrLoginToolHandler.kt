package com.example.identity.tools.auth_qr.internal.confirmqrlogin

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_qr.internal.ConfirmationCodeDigest
import com.example.identity.tools.auth_qr.internal.PairingCodeGenerator
import com.example.identity.tools.auth_qr.internal.QrLoginRequestRepository
import com.example.identity.tools.auth_qr.internal.QrLoginStatus
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import com.example.identity.tools.auth_qr.api.v1.QrPairingStep
import com.example.identity.contract.tool_api.MissingFields

/**
 * toolId=approve-qr: approve or decline a pending QR pairing (docs/verfahren/qr.md). `input`
 * resolves the pairing code, `confirm` takes the decision, `showCode` shows the confirmation code
 * for the browser. Approving alone logs no browser in.
 */
@Component
class ConfirmQrLoginToolHandler(
    private val sessions: ToolSessionData,
    private val qrLoginRequestRepository: QrLoginRequestRepository,
    private val confirmationCodeDigest: ConfirmationCodeDigest,
    private val clock: Clock,
) {

    /**
     * A known [pairingCode] (e.g. from the demo link) skips the `input` step. It is resolved like a
     * manual entry, so an unknown or expired code falls back to `input` instead of failing activation.
     */
    @Transactional
    fun start(toolSessionId: ToolSessionId, pairingCode: String? = null): ToolOutcome {
        val data = ConfirmQrLoginToolSession()
        sessions.save(toolSessionId, data)
        if (pairingCode.isNullOrBlank()) {
            return ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("pairingCode")))
        }
        return resolvePairingCode(toolSessionId, data, pairingCode)
    }

    /**
     * [hasQrEnrollment] is resolved by the controller. Without an active `enroll-qr` opt-in no
     * pairing may be approved for the account (docs/verfahren/qr.md).
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, pairingCode: String?, decision: String?, accountId: AccountId, hasQrEnrollment: Boolean): ToolOutcome {
        val data = sessions.require<ConfirmQrLoginToolSession>(toolSessionId)

        if (data.pairingCode == null) {
            return resolvePairingCode(toolSessionId, data, pairingCode)
        }

        val resolvedCode = checkNotNull(data.pairingCode)
        // Already approved by this account: only `done` is left; the code was shown once and is gone.
        if (approvedBy(resolvedCode, accountId) && decision != DONE) return codeShownStep()
        return when (decision) {
            null -> confirmStepFor(resolvedCode)
            ACCEPT -> {
                if (!hasQrEnrollment) {
                    return ToolOutcome.Failed.NothingGuessed(Text("QR-Login ist für dieses Konto nicht aktiviert."))
                }
                val expectedAccountId = qrLoginRequestRepository.findByIdOrNull(resolvedCode)?.expectedAccountId
                if (expectedAccountId != null && expectedAccountId != accountId) {
                    // auth-qr (Step-up auf einem bereits bekannten WEB-Konto) kennt sein Zielkonto
                    // schon vorher - dann hier abbrechen statt scheinbar erfolgreich zu bestätigen
                    // und erst den WEB-Poll (AuthQrToolHandler) den Mismatch entdecken zu lassen.
                    // auth-qr-lookup setzt expectedAccountId bewusst nie, bleibt also unberührt.
                    return ToolOutcome.Failed.NothingGuessed(Text("Bestätigung passt nicht zu diesem Konto"))
                }
                val confirmationCode = PairingCodeGenerator.confirmationCode()
                val now = clock.instant()
                val rows = qrLoginRequestRepository.approveIfPending(
                    resolvedCode, accountId, confirmationCodeDigest.digest(confirmationCode), now, now.plus(CONFIRMATION_TTL)
                )
                if (rows == 1) {
                    // The one and only time the plaintext leaves the server - it is stored as a hash.
                    ToolOutcome.InProgress(nextStep = SHOW_CODE, stepData = QrPairingStep(confirmationCode = confirmationCode))
                } else {
                    ToolOutcome.Failed.NothingGuessed(Text("Anfrage wurde bereits bearbeitet oder ist abgelaufen"))
                }
            }
            DONE -> if (approvedBy(resolvedCode, accountId)) ToolOutcome.Completed.Approved() else confirmStepFor(resolvedCode)
            REJECT -> {
                val rows = qrLoginRequestRepository.denyIfPending(resolvedCode, clock.instant())
                if (rows == 1) {
                    ToolOutcome.Failed.NothingGuessed(Text("Vom Nutzer abgelehnt"))
                } else {
                    ToolOutcome.Failed.NothingGuessed(Text("Anfrage wurde bereits bearbeitet oder ist abgelaufen"))
                }
            }
            else -> error("Unbekannte decision: $decision")
        }
    }

    private fun resolvePairingCode(toolSessionId: ToolSessionId, data: ConfirmQrLoginToolSession, pairingCode: String?): ToolOutcome {
        if (pairingCode.isNullOrBlank()) {
            return ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("pairingCode")))
        }
        val request = qrLoginRequestRepository.findByIdOrNull(pairingCode)
        if (request == null || request.status != QrLoginStatus.PENDING || clock.instant().isAfter(request.expiresAt)) {
            // Stays on `input` - an unknown/expired/already-decided code is retryable, not a
            // dead end (docs/verfahren/qr.md).
            return ToolOutcome.Failed.NothingGuessed(Text("Anfrage nicht gefunden oder abgelaufen"))
        }
        sessions.save(toolSessionId, data.copy(pairingCode = pairingCode))
        return confirmStepFor(pairingCode)
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        val pairingCode = sessions.require<ConfirmQrLoginToolSession>(toolSessionId).pairingCode
            ?: return ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("pairingCode")))
        // This tool session only ever approves for its own channel's account - an approved request
        // here is its own approval.
        val approved = qrLoginRequestRepository.findByIdOrNull(pairingCode)
            ?.let { it.status == QrLoginStatus.APPROVED || it.status == QrLoginStatus.COMPLETED } ?: false
        return if (approved) codeShownStep() else confirmStepFor(pairingCode)
    }

    private fun confirmStepFor(pairingCode: String): ToolOutcome.InProgress =
        ToolOutcome.InProgress(nextStep = "confirm", stepData = MissingFields(listOf("decision")))

    /** After a reload: approved, but the code is not recoverable - only its hash is stored. */
    private fun codeShownStep(): ToolOutcome.InProgress =
        ToolOutcome.InProgress(nextStep = SHOW_CODE, stepData = MissingFields(listOf("decision")))

    private fun approvedBy(pairingCode: String, accountId: AccountId): Boolean =
        qrLoginRequestRepository.findByIdOrNull(pairingCode)?.let {
            it.resolvingAccountId == accountId && (it.status == QrLoginStatus.APPROVED || it.status == QrLoginStatus.COMPLETED)
        } ?: false

    companion object {
        const val ACCEPT = "accept"
        const val REJECT = "reject"
        const val DONE = "done"
        const val SHOW_CODE = "showCode"

        /** How long the browser has to type the code after the approval. */
        val CONFIRMATION_TTL: Duration = Duration.ofMinutes(2)
    }
}
