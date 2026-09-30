package com.example.identity.tools.auth_password.internal.enrollpassword

import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Tool-session-scoped working data for toolId=enroll-password (docs/06-ablaeufe.md #1 pattern). */
@Entity
@Table(schema = "auth_password", name = "enroll_tool_session")
class EnrollPasswordToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: ToolSessionId? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
