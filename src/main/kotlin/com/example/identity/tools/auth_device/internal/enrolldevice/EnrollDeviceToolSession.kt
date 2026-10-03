package com.example.identity.tools.auth_device.internal.enrolldevice

/**
 * The working data of one enroll-device run (docs/06-ablaeufe.md pattern), kept through
 * `ToolSessionData`; only an existence marker.
 */
internal data class EnrollDeviceToolSession(val started: Boolean = true)
