package com.example.identity.tools.auth_device.internal.authdevice

/** The working data of one auth-device run (docs/06-ablaeufe.md pattern), kept through `ToolSessionData`. */
internal data class AuthDeviceToolSession(
    val enrollmentRefId: String? = null,
)
