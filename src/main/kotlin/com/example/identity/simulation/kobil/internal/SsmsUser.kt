package com.example.identity.simulation.kobil.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * A user and its bound device, as the foreign system holds them. In the `kobil` schema, so
 * the only way to it is [com.example.identity.simulation.kobil.KobilSsms]. Persisted, because every
 * `auth_kobil.enrollment` must still find its user after a restart.
 */
@Entity
@Table(schema = "kobil", name = "ssms_user")
class SsmsUser(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    @Column(name = "tenant_id", nullable = false)
    var tenantId: String = "",

    /** Whatever the relying party used to name this subject - opaque to KOBIL. */
    @Column(name = "subject_ref")
    var subjectRef: String? = null,

    @Column(name = "pin")
    var pin: String? = null,

    @Column(name = "activation_code")
    var activationCode: String? = null,

    /** Created by KOBIL on activation, never chosen by the relying party. */
    @Column(name = "device_id")
    var deviceId: String? = null,

    /** Comma-separated [com.example.identity.simulation.kobil.KobilRisk] names; empty means a clean device. */
    @Column(name = "risk_signals", nullable = false)
    var riskSignals: String = "",
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = Instant.now()
}
