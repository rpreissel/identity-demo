package com.example.identity.tools.auth_email.internal.enrollemail

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Tool session for toolId=enroll-email. Holds nothing beyond the row itself; it exists so every
 * tool run is visible and ages out through the module's retention sweep.
 */
@Entity
@Table(schema = "auth_email", name = "enroll_tool_session")
class EnrollEmailToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = null

    init {
        createdAt = Instant.now()
    }
}
