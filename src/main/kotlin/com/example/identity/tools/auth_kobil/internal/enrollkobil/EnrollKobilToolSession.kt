package com.example.identity.tools.auth_kobil.internal.enrollkobil

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Tool-session-scoped working data for toolId=enroll-kobil. Carries the minted secrets until the
 * device reports back, since the client may reload mid-flow. They die with the row after 24h
 * ([com.example.identity.tools.auth_kobil.internal.AuthKobilRetentionJob]).
 */
@Entity
@Table(schema = "auth_kobil", name = "enroll_tool_session")
class EnrollKobilToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,

    @Column(name = "kobil_tenant_id", nullable = false)
    var kobilTenantId: String = "",

    @Column(name = "kobil_user_id", nullable = false)
    var kobilUserId: String = "",

    @Column(name = "activation_code", nullable = false)
    var activationCode: String = "",

    @Column(name = "pin", nullable = false)
    var pin: String = "",

    /**
     * Plaintext while the setup runs: at this point it is not yet a credential but a value the
     * client still has to receive, and a reload must not cut the flow off. Only its hash survives
     * into [com.example.identity.tools.auth_kobil.internal.KobilEnrollment].
     */
    @Column(name = "unlock_secret", nullable = false)
    var unlockSecret: String = "",
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
