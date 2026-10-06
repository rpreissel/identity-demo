package com.example.identity.core.orchestrator.session

import com.example.identity.core.account.AccountDataCipher
import com.example.identity.core.account.AccountSealer
import io.mockk.every
import io.mockk.mockk

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
