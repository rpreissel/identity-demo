package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.kms.AccountSealer
import com.example.identity.core.account.AccountDataCipher
import io.mockk.every
import io.mockk.mockk
import com.example.identity.contract.tool_api.ids.AccountId
import java.time.Instant
import java.util.UUID

/**
 * The vault over a cipher that seals nothing, so unit tests read and write tokens as plain
 * strings through [accessToken] and [refreshToken]. Spring tests use the real vault.
 */
val plainTokenVault: AppTokenVault = AppTokenVault(
    mockk<AccountDataCipher> {
        every { forAccount(any()) } returns object : AccountSealer {
            override fun seal(purpose: String, plaintext: ByteArray) = plaintext
            override fun open(purpose: String, sealed: ByteArray) = sealed
        }
    }
)

var AppTokenSession.accessToken: String?
    get() = sealedAccessToken?.let { String(it) }
    set(value) {
        sealedAccessToken = value?.toByteArray()
    }

var AppTokenSession.refreshToken: String?
    get() = sealedRefreshToken?.let { String(it) }
    set(value) {
        sealedRefreshToken = value?.toByteArray()
    }

/** An [AppTokenSession] as the repository hands it out: with its id, which binds its tokens. */
fun storedAppTokenSession(accountId: AccountId?, keycloakSessionId: String? = null, now: Instant) =
    AppTokenSession(accountId = accountId, keycloakSessionId = keycloakSessionId, now = now).apply { appTokenSessionId = UUID.randomUUID() }
