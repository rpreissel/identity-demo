package com.example.identity.tools.auth_kobil.internal.authkobil

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.device.UserVerification
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Tool-session-scoped working data for toolId=auth-kobil. */
@Entity
@Table(schema = "auth_kobil", name = "auth_tool_session")
class AuthKobilToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: ToolSessionId? = null,

    @Column(name = "enrollment_ref_id")
    var enrollmentRefId: String? = null,
    createdAt: Instant,
) {
    /**
     * Null until the PIN has been released; then the access means that unlocked it. One nullable
     * value instead of a flag beside a path name, so the two cannot disagree. It is also the
     * source of the amr entry the completed run reports.
     */
    @Column(name = "user_verification", length = 16)
    var userVerification: String? = null

    @Column(name = "pin_release_expires_at")
    var pinReleaseExpiresAt: Instant? = null

    @Column(name = "created_at", nullable = false)
    var createdAt: Instant = createdAt

    /** The release, or null while there is none that still counts - an expired one is none. */
    fun liveRelease(now: Instant): UserVerification? {
        val expiry = pinReleaseExpiresAt ?: return null
        if (expiry.isBefore(now)) return null
        return UserVerification.fromWireValue(userVerification)
    }

    fun release(userVerification: UserVerification, expiresAt: Instant) {
        this.userVerification = userVerification.wireValue
        this.pinReleaseExpiresAt = expiresAt
    }
}
