package com.example.identity.tools.auth_password.internal.authpasswordlookup

import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Tool session for toolId=auth-password-lookup; only an existence marker for a single-step tool. */
@Entity
@Table(schema = "auth_password", name = "lookup_tool_session")
class AuthPasswordLookupToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: ToolSessionId? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
