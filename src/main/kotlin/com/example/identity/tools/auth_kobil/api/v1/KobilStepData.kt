package com.example.identity.tools.auth_kobil.api.v1

import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.stepData

/**
 * The step shapes `auth_kobil` produces, declared in the producing module. `tenantId`/`kobilUserId`
 * go straight to the KOBIL SDK; they identify the user at the provider.
 */
internal val KobilStepData = mapOf(
    "kobil-unlock" to stepData<KobilUnlockStep>(
        "The app must unlock the backend-held PIN; these are the accepted ways.",
        "unlockOptions" to listOf("biometric", "password"),
    ),
    "kobil-otp" to stepData<KobilOtpStep>(
        "Waiting for the one-time password the KOBIL SDK produced.",
        "missingFields" to listOf("otp"),
    ),
    "kobil-activation" to stepData<KobilActivationStep>("What the KOBIL SDK needs to activate this device."),
)

/** The app must unlock the stored PIN before it can be used - these are the ways it may. */
data class KobilUnlockStep(
    val unlockOptions: List<String>,
    val tenantId: String,
    val kobilUserId: String
) : StepData

/** The SDK produced an OTP and it is now expected back. */
data class KobilOtpStep(
    val missingFields: List<String>,
    val tenantId: String,
    val kobilUserId: String,
    /**
     * The released PIN, handed to the SDK for this one call (ADR-21/ADR-22). Part of the step, not
     * of the demo block: the app needs it to work at all, in every deployment.
     */
    val kobilPin: String? = null
) : StepData

/**
 * Setting up a KOBIL binding: everything the SDK's activation call needs. `pin` and `unlockSecret`
 * go to the SDK, the user never sees either (ADR-21/ADR-22). They are part of the step, not the demo block.
 */
data class KobilActivationStep(
    val missingFields: List<String>,
    val tenantId: String,
    val kobilUserId: String,
    val activationCode: String,
    val pin: String,
    val unlockSecret: String? = null
) : StepData
