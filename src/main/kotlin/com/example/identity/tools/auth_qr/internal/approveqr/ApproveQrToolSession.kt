package com.example.identity.tools.auth_qr.internal.approveqr

/**
 * The working data of one approve-qr run, kept through `ToolSessionData`. [pairingCode] is `null`
 * until the `input` step resolves a valid one; that moves the tool from `input` to `confirm`.
 */
internal data class ApproveQrToolSession(
    val pairingCode: String? = null,
)
