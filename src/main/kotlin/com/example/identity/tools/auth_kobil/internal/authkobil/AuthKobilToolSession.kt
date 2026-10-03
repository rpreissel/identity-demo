package com.example.identity.tools.auth_kobil.internal.authkobil

import com.example.identity.contract.tool_api.device.UserVerification
import java.time.Instant

/** The working data of one auth-kobil run, kept through `ToolSessionData`. */
internal data class AuthKobilToolSession(
    val enrollmentRefId: String? = null,
    /**
     * Null until the PIN has been released; then the access means that unlocked it. One nullable
     * value instead of a flag beside a path name, so the two cannot disagree. It is also the
     * source of the amr entry the completed run reports.
     */
    val userVerification: String? = null,
    val pinReleaseExpiresAt: Instant? = null,
) {
    /** The release, or null while there is none that still counts - an expired one is none. */
    fun liveRelease(now: Instant): UserVerification? {
        val expiry = pinReleaseExpiresAt ?: return null
        if (expiry.isBefore(now)) return null
        return UserVerification.fromWireValue(userVerification)
    }

    /** This session with [userVerification] released until [expiresAt]; the caller saves it. */
    fun released(userVerification: UserVerification, expiresAt: Instant): AuthKobilToolSession =
        copy(userVerification = userVerification.wireValue, pinReleaseExpiresAt = expiresAt)
}
