package com.example.identity.tools.auth_qr.internal.enrollqr

/**
 * The working data of one enroll-qr run (docs/06-ablaeufe.md #1 pattern), kept through
 * `ToolSessionData`; only an existence marker.
 */
internal data class EnrollQrToolSession(val started: Boolean = true)
