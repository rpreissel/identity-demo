package com.example.identity.tools.auth_password.internal.authpassword

/** The working data of one auth-password run (docs/06-ablaeufe.md #1 pattern), kept through `ToolSessionData`. */
internal data class AuthPasswordToolSession(
    val enrollmentRefId: String,
)
