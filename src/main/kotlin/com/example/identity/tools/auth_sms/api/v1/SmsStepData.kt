package com.example.identity.tools.auth_sms.api.v1

import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.stepData

/** The step shapes `auth_sms` produces beyond the shared ones, declared in the producing module. */
internal val SmsStepData = mapOf(
    "enroll-sms" to stepData<EnrollSmsStep>(
        "Waiting for the phone number or its TAN; `replaces` says the account already has a number.",
        "missingFields" to listOf("phoneNumber"),
        "replaces" to false,
    ),
)

/** What `enroll-sms` waits for, and whether the new number replaces an active one. */
data class EnrollSmsStep(
    val missingFields: List<String>,
    val replaces: Boolean,
) : StepData
