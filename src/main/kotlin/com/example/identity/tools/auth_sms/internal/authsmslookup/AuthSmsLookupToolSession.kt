package com.example.identity.tools.auth_sms.internal.authsmslookup

import com.example.identity.contract.tool_api.ids.AccountId
import java.time.Instant

/**
 * The working data of one auth-sms-lookup run, kept through `ToolSessionData`. [accountId] is only
 * known once the first PATCH resolved the email.
 */
internal data class AuthSmsLookupToolSession(
    val accountId: AccountId? = null,
    val issuedTanHash: String? = null,
    val tanExpiresAt: Instant? = null,
)
