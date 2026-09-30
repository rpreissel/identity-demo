package com.example.identity.tools.auth_sms.internal.authsmslookup

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * Tool-session-scoped working data for toolId=auth-sms-lookup. [accountId] is only known once the
 * first PATCH resolved the email.
 */
@Entity
@Table(schema = "auth_sms", name = "lookup_tool_session")
class AuthSmsLookupToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: ToolSessionId? = null,

    @Column(name = "account_id")
    var accountId: AccountId? = null,

    @Column(name = "issued_tan_hash")
    var issuedTanHash: String? = null,

    @Column(name = "tan_expires_at")
    var tanExpiresAt: Instant? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
