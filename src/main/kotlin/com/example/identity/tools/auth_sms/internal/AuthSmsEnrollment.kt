package com.example.identity.tools.auth_sms.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** The [com.example.identity.contract.tool_api.EnrollmentRef.type] enroll-sms writes and the login tools read back. */
internal const val SMS_ENROLLMENT_TYPE = "auth_sms.enrollment"

/**
 * Long-lived, confirmed SMS enrollment (docs/verfahren/sms.md). Exists only after a
 * successful TAN check, so it is valid by definition - no `validated` flag, no `updatedAt`.
 */
@Entity
@Table(schema = "auth_sms", name = "enrollment")
class AuthSmsEnrollment(
    var phoneNumber: String? = null,
    createdAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var createdAt: Instant? = createdAt
}
