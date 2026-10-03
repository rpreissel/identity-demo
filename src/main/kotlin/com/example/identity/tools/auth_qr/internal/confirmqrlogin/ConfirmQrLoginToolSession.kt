package com.example.identity.tools.auth_qr.internal.confirmqrlogin

/**
 * The working data of one approve-qr run, kept through `ToolSessionData`. [pairingCode] is `null`
 * until the `input` step resolves a valid one; that moves the tool from `input` to `confirm`.
 */
internal data class ConfirmQrLoginToolSession(
    val pairingCode: String? = null,
)
