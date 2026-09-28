package com.example.identity.tools.auth_password.internal.enrollpassword

import com.example.identity.tools.auth_password.internal.PasswordPolicy

import com.example.identity.tools.auth_password.DEMO_PASSWORD
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.StepData

/**
 * Single-shot flow (docs/03-tool-architektur.md #3, the optional Flow pattern): a chosen password
 * is self-verifying, so there is no confirmation step and thus no persisted partial state -
 * [decide] works from the input alone.
 */
internal data class EnrollPasswordInput(val password: String? = null)

/** What [EnrollPasswordFlow.decide] concluded should happen. */
internal sealed interface EnrollPasswordDecision {
    data class Enroll(val password: String) : EnrollPasswordDecision
    /** Fails the password rules ([com.example.identity.tools.auth_password.internal.PasswordPolicy]). */
    data class Rejected(val rejection: PasswordPolicy.Rejection) : EnrollPasswordDecision
    data object Unchanged : EnrollPasswordDecision
}

internal object EnrollPasswordFlow {

    fun decide(input: EnrollPasswordInput): EnrollPasswordDecision {
        val value = input.password ?: return EnrollPasswordDecision.Unchanged
        return PasswordPolicy.check(value)?.let { EnrollPasswordDecision.Rejected(it) } ?: EnrollPasswordDecision.Enroll(value)
    }

    /** Same derivation for start/patch/read - one place turns the state into `next.step`/`stepData`. */
    fun describe(): Pair<String, StepData> = "enroll" to MissingFields(listOf("password"))

    /** The fixed demo password, so a tester never has to remember one - never part of the step. */
    fun demo(): Map<String, Any?> = mapOf("password" to DEMO_PASSWORD)

}
