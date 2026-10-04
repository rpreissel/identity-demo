package com.example.identity.tools.auth_kobil.internal.enrollkobil

import com.example.identity.contract.tool_api.device.UserVerification

/**
 * What the client still owes at the `activate` step (docs/03-tool-architektur.md #6): only the
 * confirmation that the SDK ran and the biometric consent. Everything it needs was handed out at start.
 */
internal data object EnrollKobilState {
    val step: String get() = "activate"

    fun describe(): Pair<String, List<String>> = step to MISSING_FIELDS

    private val MISSING_FIELDS = listOf("activated", "biometricConsent")
}

internal sealed interface EnrollKobilDecision {
    /**
     * KOBIL knows a device for this user, so the credential can be written. Without
     * [biometricConsent] no server-side counterpart of the unlock secret is stored; that is the
     * whole consent mechanism.
     */
    data class Enroll(val deviceId: String, val biometricConsent: Boolean) : EnrollKobilDecision {
        /** What the run reports as its access means - derived from the consent, never a second input. */
        val userVerification: UserVerification
            get() = if (biometricConsent) UserVerification.BIOMETRIC else UserVerification.PIN
    }

    /**
     * Nothing to decide yet: the SDK has not run, or KOBIL has no device for this user. Not a
     * `Failed`, since nothing was guessed and a page reload must not cost an attempt.
     */
    data object Unchanged : EnrollKobilDecision

}

internal object EnrollKobilFlow {

    fun decide(activated: Boolean?, biometricConsent: Boolean?, deviceId: String?): EnrollKobilDecision {
        if (activated != true || biometricConsent == null || deviceId == null) return EnrollKobilDecision.Unchanged
        return EnrollKobilDecision.Enroll(deviceId, biometricConsent)
    }
}
