package com.example.identity.tools.auth_qr.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * PENDING -> APPROVED (the app approved and showed its confirmation code) -> COMPLETED (the
 * browser entered it). DENIED and EXPIRED end it; so do too many wrong codes (EXPIRED).
 */
enum class QrLoginStatus { PENDING, APPROVED, COMPLETED, DENIED, EXPIRED }

/**
 * Connects a WEB `auth-qr`/`auth-qr-lookup` activation to an APP `confirm-qr-login` decision.
 * [pairingCode] is the primary key and the lookup capability (docs/07-betrieb.md #5).
 * [confirmationCodeHash] is the code in the other direction, typed into the browser;
 * [confirmationAttempts] bounds guessing it. [expectedAccountId] is set only by `auth-qr`, and the
 * approval must match it, so a different account can never take over.
 */
@Entity
@Table(schema = "auth_qr", name = "login_request")
class QrLoginRequest(
    @Id
    @Column(name = "pairing_code", nullable = false)
    var pairingCode: String? = null,

    @Column(name = "expected_account_id")
    var expectedAccountId: Long? = null
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: QrLoginStatus = QrLoginStatus.PENDING

    @Column(name = "resolving_account_id")
    var resolvingAccountId: Long? = null

    @Column(name = "confirmation_code_hash")
    var confirmationCodeHash: String? = null

    @Column(name = "confirmation_attempts", nullable = false)
    var confirmationAttempts: Int = 0

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
