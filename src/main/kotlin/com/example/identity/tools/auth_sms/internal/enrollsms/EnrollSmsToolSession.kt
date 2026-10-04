package com.example.identity.tools.auth_sms.internal.enrollsms

import java.time.Instant

/**
 * The working data of one enroll-sms run (docs/06-ablaeufe.md #1), kept through `ToolSessionData`.
 * [replaces]: the account had a number when the run began.
 */
internal data class EnrollSmsToolSession(
    val phoneNumber: String? = null,
    val issuedTanHash: String? = null,
    val tanExpiresAt: Instant? = null,
    val replaces: Boolean = false,
)
