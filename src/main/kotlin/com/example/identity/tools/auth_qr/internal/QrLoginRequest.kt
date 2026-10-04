package com.example.identity.tools.auth_qr.internal

import com.example.identity.contract.tool_api.ids.AccountId
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
 * Connects a WEB `auth-qr`/`auth-qr-lookup` activation to an APP `approve-qr` decision.
 * [pairingCode] is the primary key and the lookup capability (docs/verfahren/qr.md).
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
    var expectedAccountId: AccountId? = null,
    createdAt: Instant
) {
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: QrLoginStatus = QrLoginStatus.PENDING

    @Column(name = "resolving_account_id")
    var resolvingAccountId: AccountId? = null

    @Column(name = "confirmation_code_hash")
    var confirmationCodeHash: String? = null

    @Column(name = "confirmation_attempts", nullable = false)
    var confirmationAttempts: Int = 0

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt

    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant? = null
}
