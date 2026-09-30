package com.example.identity.tools.auth_qr.internal.authqrlookup

import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Tool-session-scoped working data for toolId=auth-qr-lookup: which QrLoginRequest it waits on. */
@Entity
@Table(schema = "auth_qr", name = "lookup_tool_session")
class AuthQrLookupToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: ToolSessionId? = null,

    @Column(name = "pairing_code", nullable = false)
    var pairingCode: String? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
