package com.example.identity.tools.auth_password.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** The [com.example.identity.contract.tool_api.EnrollmentRef.type] enroll-password writes and the login tools read back. */
internal const val PASSWORD_ENROLLMENT_TYPE = "auth_password.enrollment"

/**
 * Long-lived password credential (docs/06-ablaeufe.md #1). A chosen password is self-verifying, so
 * there is no unconfirmed interim record. No identifier field: the account's confirmed email is the
 * identifier, and enroll-password requires it.
 */
@Entity
@Table(schema = "auth_password", name = "enrollment")
class AuthPasswordEnrollment(
    var passwordHash: String? = null,
    createdAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var createdAt: Instant? = createdAt
}
