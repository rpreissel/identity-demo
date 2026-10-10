package com.example.identity.tools.auth_device.internal.enrolldevice

import com.example.identity.contract.tool_api.device.DevicePublicKey
import com.example.identity.contract.tool_api.ToolStep
import com.example.identity.contract.tool_api.device.UserVerification

/**
 * Single-shot flow (docs/03-tool-architektur.md #6): the device proof arrives already verified, so
 * [decide] always enrolls. A type without fields, so it has the same `describe()` as other states.
 */
internal data object EnrollDeviceState {
    val step: String get() = "enroll"

    /** No `stepData` needed: the fields come from a signed device API call, not a form the client fills incrementally. */
    fun describe(): ToolStep = ToolStep(step, null)
}

internal data class EnrollDeviceInput(val devicePublicKey: DevicePublicKey, val userVerification: UserVerification, val deviceBindingKeyRef: String, val label: String?)

internal sealed interface EnrollDeviceDecision {
    data class Enroll(val devicePublicKey: DevicePublicKey, val userVerification: UserVerification, val deviceBindingKeyRef: String, val label: String?) : EnrollDeviceDecision

    /**
     * The credential key is the channel's DPoP key. A second factor that is the same key as the
     * channel binding adds nothing: whoever holds the one holds the other.
     */
    data object SameKeyAsChannel : EnrollDeviceDecision
}

internal object EnrollDeviceFlow {

    fun decide(input: EnrollDeviceInput): EnrollDeviceDecision =
        if (input.devicePublicKey.thumbprint == input.deviceBindingKeyRef) {
            EnrollDeviceDecision.SameKeyAsChannel
        } else {
            EnrollDeviceDecision.Enroll(input.devicePublicKey, input.userVerification, input.deviceBindingKeyRef, input.label)
        }

}
