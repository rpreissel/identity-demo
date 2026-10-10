package com.example.identity.tools.auth_invite.internal

/**
 * The working data of one auth-invite-lookup run, kept through `ToolSessionData`; only an existence
 * marker for a single-step tool. The one-time password is never stored.
 */
internal data class AuthInviteLookupToolSession(val started: Boolean = true)
