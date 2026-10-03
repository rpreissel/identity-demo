package com.example.identity.tools.auth_sms.internal.authsms

import java.time.Instant

/** The working data of one auth-sms run (docs/06-ablaeufe.md #1), kept through `ToolSessionData`. */
internal data class AuthSmsToolSession(
    val enrollmentRefId: String? = null,
    val issuedTanHash: String? = null,
    val tanExpiresAt: Instant? = null,
)
