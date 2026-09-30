package com.example.identity.tools.auth_sms.internal.enrollsms

import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Tool-session-scoped working data for toolId=enroll-sms (docs/06-ablaeufe.md #1). */
@Entity
@Table(schema = "auth_sms", name = "enroll_tool_session")
class EnrollSmsToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: ToolSessionId? = null,

    @Column(name = "phone_number")
    var phoneNumber: String? = null,

    @Column(name = "issued_tan_hash")
    var issuedTanHash: String? = null,

    @Column(name = "tan_expires_at")
    var tanExpiresAt: Instant? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
