package com.example.identity.tools.auth_qr.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** The [com.example.identity.contract.tool_api.EnrollmentRef.type] enroll-qr writes - a pure opt-in marker, no secret. */
internal const val QR_OPTIN_ENROLLMENT_TYPE = "auth_qr.enrollment"

/**
 * The long-lived row `enroll-qr` creates: a pure opt-in marker ("this account allows QR login"),
 * never a secret (docs/03-tool-architektur.md). Its only content is its existence.
 */
@Entity
@Table(schema = "auth_qr", name = "enrollment")
class QrOptIn(createdAt: Instant) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var createdAt: Instant? = createdAt
}
