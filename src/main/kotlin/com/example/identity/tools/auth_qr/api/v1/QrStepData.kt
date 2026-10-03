package com.example.identity.tools.auth_qr.api.v1

import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.stepData

/** The step shapes `auth_qr` produces. */
internal val QrStepData = mapOf(
    "qr-pairing" to stepData<QrPairingStep>(
        "A QR pairing in progress: the pairing code (browser), or the confirmation code to type into the browser (app, once).",
        "pairingCode" to "7F3K92QL",
        "confirmationCode" to "482913",
    ),
)

/**
 * What a QR pairing step shows: the browser the [pairingCode], the app after approving the
 * [confirmationCode], once, in that one response; it is stored only as a hash.
 */
data class QrPairingStep(
    val pairingCode: String? = null,
    val confirmationCode: String? = null
) : StepData
