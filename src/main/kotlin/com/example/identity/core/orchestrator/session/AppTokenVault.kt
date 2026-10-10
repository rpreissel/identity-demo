package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.kms.AccountSealer
import com.example.identity.core.account.AccountDataCipher
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import javax.crypto.AEADBadTagException

/**
 * The cached tokens of an [AppTokenSession] at rest: sealed under the owning account's key
 * (ADR-53, `AccountDataCipher`), each bound to its purpose and its session, so a stolen table row
 * yields no usable token, a refresh token cannot pass as an access token, and no token moves to
 * another session of the account. [forSession] opens the account's key once for everything a caller
 * does with the session. Nulling a token needs no key.
 */
@Component
class AppTokenVault(private val cipher: AccountDataCipher) {

    fun forSession(session: AppTokenSession): SessionTokens = SessionTokens(session, cipher.forAccount(checkNotNull(session.accountId)))

    fun accessTokenOf(session: AppTokenSession): String? = forSession(session).accessToken

    class SessionTokens internal constructor(private val session: AppTokenSession, private val sealer: AccountSealer) {
        private val sessionId = checkNotNull(session.appTokenSessionId) { "an AppTokenSession is stored before it holds tokens" }

        var accessToken: String?
            get() = session.sealedAccessToken?.let { open("$ACCESS:$sessionId", it) }
            set(value) {
                session.sealedAccessToken = value?.let { sealer.seal("$ACCESS:$sessionId", it.toByteArray()) }
            }

        var refreshToken: String?
            get() = session.sealedRefreshToken?.let { open("$REFRESH:$sessionId", it) }
            set(value) {
                session.sealedRefreshToken = value?.let { sealer.seal("$REFRESH:$sessionId", it.toByteArray()) }
            }

        /**
         * A token that does not open under this session counts as absent, so the session fetches a
         * fresh one from Keycloak: a cache entry is never worth a failed request.
         */
        private fun open(purpose: String, sealed: ByteArray): String? = try {
            String(sealer.open(purpose, sealed))
        } catch (_: AEADBadTagException) {
            log.warn("Cached token of AppTokenSession {} does not open under its binding; treated as absent", sessionId)
            null
        }
    }

    private companion object {
        const val ACCESS = "app-token:access"
        const val REFRESH = "app-token:refresh"
        val log = LoggerFactory.getLogger(AppTokenVault::class.java)
    }
}
