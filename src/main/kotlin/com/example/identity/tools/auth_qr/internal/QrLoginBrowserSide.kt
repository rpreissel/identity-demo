package com.example.identity.tools.auth_qr.internal

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.tools.auth_qr.QR_LOGIN_TTL
import com.example.identity.contract.texts.Text
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * The browser's half of a QR login, shared by `auth-qr` and `auth-qr-lookup` (docs/verfahren/qr.md):
 * `waitForApp` shows the pairing code, `enterCode` takes the confirmation code the app shows. The
 * second step is the point: a victim approving an attacker's pairing from a link would otherwise
 * hand over the account; this way they would also have to type into the attacker's browser.
 */
@Component
class QrLoginBrowserSide(
    private val requests: QrLoginRequestRepository,
    private val confirmationCodeDigest: ConfirmationCodeDigest,
    private val clock: Clock,
) {

    sealed interface State {
        data object WaitingForApp : State
        data object EnterCode : State
        /** [expectedAccountId] as opened - `auth-qr` must see exactly that account confirm. */
        data class Confirmed(val accountId: AccountId, val expectedAccountId: AccountId?) : State
        data class Failed(val reason: Text) : State
    }

    /** Opens a new request; returns its pairing code. [expectedAccountId] only for `auth-qr`. */
    @Transactional
    fun open(expectedAccountId: AccountId?): String {
        val pairingCode = PairingCodeGenerator.pairingCode()
        val now = clock.instant()
        requests.save(
            QrLoginRequest(pairingCode = pairingCode, expectedAccountId = expectedAccountId, createdAt = now)
                .apply { expiresAt = now.plus(QR_LOGIN_TTL) }
        )
        return pairingCode
    }

    /** Each browser PATCH: an empty poll, or - once approved - the typed [confirmationCode]. */
    @Transactional
    fun advance(pairingCode: String, confirmationCode: String?): State {
        val request = requests.findByIdOrNull(pairingCode) ?: return State.Failed(EXPIRED)
        val now = clock.instant()
        val expired = request.expiresAt?.let { now.isAfter(it) } ?: true
        return when (request.status) {
            QrLoginStatus.PENDING -> if (expired) State.Failed(EXPIRED) else State.WaitingForApp
            QrLoginStatus.APPROVED -> when {
                expired -> State.Failed(EXPIRED)
                confirmationCode.isNullOrBlank() -> State.EnterCode
                requests.completeIfConfirmed(pairingCode, confirmationCodeDigest.digest(confirmationCode), now) == 1 ->
                    State.Confirmed(
                        checkNotNull(request.resolvingAccountId) { "APPROVED QrLoginRequest without resolvingAccountId" },
                        request.expectedAccountId
                    )
                else -> {
                    requests.countWrongConfirmation(pairingCode, PairingCodeGenerator.MAX_CONFIRMATION_ATTEMPTS)
                    val burned = request.confirmationAttempts + 1 >= PairingCodeGenerator.MAX_CONFIRMATION_ATTEMPTS
                    State.Failed(if (burned) BURNED else WRONG_CODE)
                }
            }
            // Completed once already - a replayed code must not log in a second browser.
            QrLoginStatus.COMPLETED -> State.Failed(ALREADY_USED)
            QrLoginStatus.DENIED -> State.Failed(Text("Vom Nutzer abgelehnt"))
            QrLoginStatus.EXPIRED -> State.Failed(EXPIRED)
        }
    }

    private companion object {
        val EXPIRED = Text("QR-Code abgelaufen")
        val WRONG_CODE = Text("Bestätigungscode falsch")
        val BURNED = Text("Zu viele falsche Bestätigungscodes. Bitte starten Sie die Anmeldung per QR-Code neu.")
        val ALREADY_USED = Text("Anfrage wurde bereits bearbeitet oder ist abgelaufen")
    }
}
