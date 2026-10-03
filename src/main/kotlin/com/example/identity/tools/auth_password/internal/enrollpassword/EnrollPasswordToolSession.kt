package com.example.identity.tools.auth_password.internal.enrollpassword

/**
 * The working data of one enroll-password run (docs/06-ablaeufe.md #1 pattern), kept through
 * `ToolSessionData`; only an existence marker, the password itself is never kept here.
 */
internal data class EnrollPasswordToolSession(val started: Boolean = true)
