package com.example.identity.core.orchestrator.session

import com.example.identity.core.account.AccountDataCipher
import io.mockk.every
import io.mockk.mockk

/**
 * The vault over a cipher that seals nothing, so unit tests read and write tokens as plain
 * strings through [accessToken] and [refreshToken]. Spring tests use the real vault.
 */
val plainTokenVault: AppTokenVault = AppTokenVault(
    mockk<AccountDataCipher> {
        every { seal(any(), any(), any()) } answers { thirdArg() }
        every { open(any(), any(), any()) } answers { thirdArg() }
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
