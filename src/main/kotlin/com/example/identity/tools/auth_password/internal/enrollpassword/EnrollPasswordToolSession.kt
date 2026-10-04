package com.example.identity.tools.auth_password.internal.enrollpassword

/**
 * The working data of one enroll-password run (docs/06-ablaeufe.md #1 pattern), kept through
 * `ToolSessionData`; the password itself is never kept here. [replaces]: the account had a
 * password when the run began.
 */
internal data class EnrollPasswordToolSession(val started: Boolean = true, val replaces: Boolean = false)
