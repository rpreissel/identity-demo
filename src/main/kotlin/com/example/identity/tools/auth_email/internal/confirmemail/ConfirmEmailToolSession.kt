package com.example.identity.tools.auth_email.internal.confirmemail

import java.time.Instant

/** The working data of one confirm-email run (mirrors auth_sms's EnrollSmsToolSession), kept through `ToolSessionData`. */
internal data class ConfirmEmailToolSession(
    val email: String? = null,
    val issuedCodeHash: String? = null,
    val codeExpiresAt: Instant? = null,
)
