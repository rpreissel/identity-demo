package com.example.identity.tools.auth_kobil.internal.authkobil

import com.example.identity.simulation.kobil.KobilOtpVerification
import com.example.identity.contract.tool_api.ToolStep
import com.example.identity.simulation.kobil.KobilRisk
import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.tools.auth_kobil.api.v1.KobilOtpStep
import com.example.identity.tools.auth_kobil.api.v1.KobilUnlockStep

/**
 * The two steps of auth-kobil: in `unlock` the client proves it may have the PIN, in `otp` it
 * returns KOBIL's reference. No `stepData` holds the PIN, because this state is rebuilt on every read.
 */
internal sealed interface AuthKobilState {
    val step: String

    fun describe(): ToolStep

    /**
     * [options] are the ways this credential can actually be unlocked; a missing means would only
     * burn a login attempt. Revealing whether the account has a password is accepted: only a caller
     * whose key matches an enrolled credential gets here. The KOBIL user id lets the app find its
     * locally stored secret.
     */
    data class Unlock(
        val tenantId: String,
        val kobilUserId: String,
        val options: List<String>,
    ) : AuthKobilState {
        override val step get() = "unlock"

        override fun describe(): ToolStep =
            ToolStep(step, KobilUnlockStep(options, tenantId, kobilUserId))
    }

    data class AwaitingOtp(val tenantId: String, val kobilUserId: String) : AuthKobilState {
        override val step get() = "otp"

        override fun describe(): ToolStep =
            ToolStep(step, KobilOtpStep(listOf("otp"), tenantId, kobilUserId))
    }
}

internal sealed interface AuthKobilDecision {
    /** Identifier matched and nothing dangerous was reported. */
    data class Complete(val userVerification: UserVerification) : AuthKobilDecision

    /** No live PIN release - never unlocked, or the window closed. */
    data object NotReleased : AuthKobilDecision

    /** KOBIL does not know this one-time password, or it was already spent. */
    data object OtpInvalid : AuthKobilDecision

    /** The assertion came from a device other than the enrolled one. */
    data object WrongDevice : AuthKobilDecision

    /** The device reported something in the blocking set. */
    data class RiskRejected(val risks: Set<KobilRisk>) : AuthKobilDecision
}

internal object AuthKobilFlow {

    /**
     * @param verification what KOBIL returned for the redeemed OTP; null for unknown, spent or foreign.
     * @param release the access means of a still-valid PIN release, null when there is none.
     * @param blockingRisks the signals this deployment refuses to authenticate through.
     */
    fun decide(
        verification: KobilOtpVerification?,
        enrolledDeviceId: String,
        release: UserVerification?,
        blockingRisks: Set<KobilRisk>,
    ): AuthKobilDecision {
        if (release == null) return AuthKobilDecision.NotReleased
        if (verification == null) return AuthKobilDecision.OtpInvalid
        if (verification.deviceId != enrolledDeviceId) return AuthKobilDecision.WrongDevice

        val dangerous = verification.risks intersect blockingRisks
        if (dangerous.isNotEmpty()) return AuthKobilDecision.RiskRejected(dangerous)

        return AuthKobilDecision.Complete(release)
    }
}
