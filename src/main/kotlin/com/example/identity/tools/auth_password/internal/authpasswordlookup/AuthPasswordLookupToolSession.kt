package com.example.identity.tools.auth_password.internal.authpasswordlookup

/**
 * The working data of one auth-password-lookup run, kept through `ToolSessionData`; only an
 * existence marker for a single-step tool.
 */
internal data class AuthPasswordLookupToolSession(val started: Boolean = true)
