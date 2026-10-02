package com.example.identity.tools.auth_kobil.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** The [com.example.identity.contract.tool_api.EnrollmentRef.type] enroll-kobil writes and auth-kobil reads back. */
internal const val KOBIL_ENROLLMENT_TYPE = "auth_kobil.enrollment"

/**
 * Long-lived, account-bound KOBIL credential. KOBIL holds the key material. This row decides whether
 * a redeemed assertion belongs here ([kobilDeviceId]), releases the PIN to the rightful app
 * ([unlockSecretHash]) and offers the credential only on its installation ([bindingKeyRef]).
 * [pin] is plaintext on purpose (ADR-22).
 */
@Entity
@Table(schema = "auth_kobil", name = "enrollment")
class KobilEnrollment(
    @Column(name = "kobil_tenant_id", nullable = false)
    var kobilTenantId: String = "",

    @Column(name = "kobil_user_id", nullable = false)
    var kobilUserId: String = "",

    /** The device identifier KOBIL created on activation - the anchor a redeemed assertion is compared against. */
    @Column(name = "kobil_device_id", nullable = false)
    var kobilDeviceId: String = "",

    @Column(name = "pin", nullable = false)
    var pin: String = "",

    /**
     * Null without consent to unlocking by biometrics; the account password is then the only way
     * in. A nullable secret rather than a flag beside one, so the two cannot disagree.
     */
    @Column(name = "unlock_secret_hash")
    var unlockSecretHash: String? = null,

    /** The enrolling channel's DPoP key: what `AuthMethodView.boundKeyRef` names and is matched at offer time. */
    @Column(name = "binding_key_ref", nullable = false)
    var bindingKeyRef: String = "",

    @Column(name = "label")
    var label: String? = null,
    createdAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = createdAt
}
