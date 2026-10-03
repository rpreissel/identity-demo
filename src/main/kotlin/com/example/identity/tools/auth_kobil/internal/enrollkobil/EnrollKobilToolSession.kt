package com.example.identity.tools.auth_kobil.internal.enrollkobil

/**
 * The working data of one enroll-kobil run, kept through `ToolSessionData`. Carries the minted
 * secrets until the device reports back, since the client may reload mid-flow. They are wiped once
 * the credential exists and otherwise die with the tool session.
 */
internal data class EnrollKobilToolSession(
    val kobilTenantId: String = "",
    val kobilUserId: String = "",
    val activationCode: String = "",
    val pin: String = "",
    /**
     * Plaintext while the setup runs: at this point it is not yet a credential but a value the
     * client still has to receive, and a reload must not cut the flow off. Only its hash survives
     * into [com.example.identity.tools.auth_kobil.internal.KobilEnrollment].
     */
    val unlockSecret: String = "",
)
