package com.example.identity.tools.auth_device.internal.authdevice

import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.contract.tool_api.StepData

/**
 * Single-shot flow (docs/03-tool-architektur.md #3): the proof arrives already verified, so the only
 * decision is whether the presented key matches the enrolled one.
 */
internal data object AuthDeviceState {
    val step: String get() = "auth"

    /** No `stepData` needed: the proof comes from a signed device API call, not a form. */
    fun describe(): Pair<String, StepData?> = step to null
}

internal sealed interface AuthDeviceDecision {
    data class Complete(val userVerification: UserVerification) : AuthDeviceDecision
    data object WrongDevice : AuthDeviceDecision
}

internal object AuthDeviceFlow {

    fun decide(submittedThumbprint: String, enrolledThumbprint: String?, userVerification: UserVerification): AuthDeviceDecision =
        if (submittedThumbprint == enrolledThumbprint) {
            AuthDeviceDecision.Complete(userVerification)
        } else {
            AuthDeviceDecision.WrongDevice
        }

}
