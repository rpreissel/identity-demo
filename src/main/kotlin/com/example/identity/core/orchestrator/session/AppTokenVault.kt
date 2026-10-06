package com.example.identity.core.orchestrator.session

import com.example.identity.core.account.AccountDataCipher
import com.example.identity.core.account.AccountSealer
import org.springframework.stereotype.Component

/**
 * The cached tokens of an [AppTokenSession] at rest: sealed under the owning account's key
 * (ADR-53, `AccountDataCipher`), each bound to its purpose, so a stolen table row yields no usable
 * token and a refresh token cannot pass as an access token. [forSession] opens the account's key
 * once for everything a caller does with the session. Nulling a token needs no key.
 */
@Component
class AppTokenVault(private val cipher: AccountDataCipher) {

    fun forSession(session: AppTokenSession): SessionTokens = SessionTokens(session, cipher.forAccount(checkNotNull(session.accountId)))

    fun accessTokenOf(session: AppTokenSession): String? = forSession(session).accessToken

    class SessionTokens internal constructor(private val session: AppTokenSession, private val sealer: AccountSealer) {
        var accessToken: String?
            get() = session.sealedAccessToken?.let { String(sealer.open(ACCESS, it)) }
            set(value) {
                session.sealedAccessToken = value?.let { sealer.seal(ACCESS, it.toByteArray()) }
            }

        var refreshToken: String?
            get() = session.sealedRefreshToken?.let { String(sealer.open(REFRESH, it)) }
            set(value) {
                session.sealedRefreshToken = value?.let { sealer.seal(REFRESH, it.toByteArray()) }
            }
    }

    private companion object {
        const val ACCESS = "app-token:access"
        const val REFRESH = "app-token:refresh"
    }
}
