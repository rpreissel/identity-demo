package com.example.identity.tools.auth_sms.internal.enrollsms

import java.time.Instant

/**
 * The working data of one enroll-sms run (docs/06-ablaeufe.md #1), kept through `ToolSessionData`.
 * [replaces]: the account had a number when the run began. [consented]: the consent of version 2
 * came with a number, so a corrected number needs none again.
 */
internal data class EnrollSmsToolSession(
    val phoneNumber: String? = null,
    val issuedTanHash: String? = null,
    val tanExpiresAt: Instant? = null,
    val replaces: Boolean = false,
    val consented: Boolean = false,
)
