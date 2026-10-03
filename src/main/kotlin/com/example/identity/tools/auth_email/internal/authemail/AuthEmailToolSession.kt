package com.example.identity.tools.auth_email.internal.authemail

import java.time.Instant

/**
 * The working data of one auth-email run, kept through `ToolSessionData`. No enrollment reference:
 * the confirmed email is the account's EMAIL anchor, so only the issued code needs remembering.
 */
internal data class AuthEmailToolSession(
    val issuedCodeHash: String? = null,
    val codeExpiresAt: Instant? = null,
)
