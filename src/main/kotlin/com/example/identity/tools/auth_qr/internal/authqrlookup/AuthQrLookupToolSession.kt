package com.example.identity.tools.auth_qr.internal.authqrlookup

/** The working data of one auth-qr-lookup run, kept through `ToolSessionData`: which QrLoginRequest it waits on. */
internal data class AuthQrLookupToolSession(
    val pairingCode: String? = null,
)
