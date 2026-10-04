package com.example.identity.tools.auth_password.api.v1

import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.stepData

/** The step shapes `auth_password` produces beyond the shared ones, declared in the producing module. */
internal val PasswordStepData = mapOf(
    "enroll-password" to stepData<EnrollPasswordStep>(
        "Waiting for the password to set; `replaces` says the account already has one.",
        "missingFields" to listOf("password"),
        "replaces" to false,
    ),
)

/** What `enroll-password` waits for, and whether the new password replaces an active one. */
data class EnrollPasswordStep(
    val missingFields: List<String>,
    val replaces: Boolean,
) : StepData
