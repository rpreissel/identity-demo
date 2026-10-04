package com.example.identity.tools.auth_device.internal

import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** The [com.example.identity.contract.tool_api.EnrollmentRef.type] enroll-device writes and auth-device reads back. */
internal const val DEVICE_ENROLLMENT_TYPE = "auth_device.enrollment"

/**
 * Long-lived, account-bound device credential: the public half of a non-extractable device key pair
 * (docs/verfahren/device.md). Each attempt is verified against a proof only the
 * matching private key could have signed.
 */
@Entity
@Table(schema = "auth_device", name = "enrollment")
class DeviceEnrollment(
    var kty: String? = null,
    var crv: String? = null,
    var x: String? = null,
    var y: String? = null,
    var thumbprint: String? = null,
    createdAt: Instant
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    var createdAt: Instant? = createdAt
}
