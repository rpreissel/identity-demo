package com.example.identity.tools.auth_qr.internal.authqr

/** The working data of one auth-qr run, kept through `ToolSessionData` - just which QrLoginRequest this session waits on. */
internal data class AuthQrToolSession(
    val pairingCode: String? = null,
)
