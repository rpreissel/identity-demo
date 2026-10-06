package com.example.identity.core.orchestrator.session

import com.example.identity.core.account.AccountDataCipher
import org.springframework.stereotype.Component

/**
 * The cached tokens of an [AppTokenSession] at rest: sealed under the owning account's key
 * (ADR-52, `AccountDataCipher`), each bound to its purpose, so a stolen table row yields no usable
 * token and a refresh token cannot pass as an access token. Nulling a token needs no key.
 */
@Component
class AppTokenVault(private val cipher: AccountDataCipher) {

    fun accessTokenOf(session: AppTokenSession): String? = session.sealedAccessToken?.let { open(session, ACCESS, it) }

    fun refreshTokenOf(session: AppTokenSession): String? = session.sealedRefreshToken?.let { open(session, REFRESH, it) }

    fun storeAccessToken(session: AppTokenSession, token: String?) {
        session.sealedAccessToken = token?.let { seal(session, ACCESS, it) }
    }

    fun storeRefreshToken(session: AppTokenSession, token: String?) {
        session.sealedRefreshToken = token?.let { seal(session, REFRESH, it) }
    }

    private fun seal(session: AppTokenSession, purpose: String, token: String): ByteArray =
        cipher.seal(checkNotNull(session.accountId), purpose, token.toByteArray())

    private fun open(session: AppTokenSession, purpose: String, sealed: ByteArray): String =
        String(cipher.open(checkNotNull(session.accountId), purpose, sealed))

    private companion object {
        const val ACCESS = "app-token:access"
        const val REFRESH = "app-token:refresh"
    }
}
