package com.example.identity.tools.auth_qr.internal.confirmqrlogin

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Tool-session-scoped working data for toolId=confirm-qr-login. [pairingCode] is `null` until the
 * `input` step resolves a valid one; that moves the tool from `input` to `confirm`.
 */
@Entity
@Table(schema = "auth_qr", name = "confirm_tool_session")
class ConfirmQrLoginToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,
    createdAt: Instant
) {
    @Column(name = "pairing_code")
    var pairingCode: String? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
