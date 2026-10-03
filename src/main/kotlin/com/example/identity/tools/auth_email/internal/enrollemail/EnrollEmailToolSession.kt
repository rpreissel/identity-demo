package com.example.identity.tools.auth_email.internal.enrollemail

/**
 * The working data of one enroll-email run, kept through `ToolSessionData`. Holds nothing but the
 * mark that the run started; it exists so every tool run is visible.
 */
internal data class EnrollEmailToolSession(val started: Boolean = true)
