package com.example.identity.tools.auth_email.internal.authemaillookup

import com.example.identity.contract.tool_api.ids.AccountId
import java.time.Instant

/**
 * The working data of one auth-email-lookup run, kept through `ToolSessionData`. [accountId] is only
 * known once the first PATCH resolved the email.
 */
internal data class AuthEmailLookupToolSession(
    val accountId: AccountId? = null,
    val issuedCodeHash: String? = null,
    val codeExpiresAt: Instant? = null,
)
