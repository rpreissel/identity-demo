package com.example.identity.tools.auth_password.internal.authpassword

import com.example.identity.tools.auth_password.DEMO_PASSWORD
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.StepData

/** What one PATCH submitted. */
internal data class AuthPasswordInput(val password: String? = null)

/**
 * What [AuthPasswordFlow.decide] concluded. The hash comparison needs the enrollment row, so
 * [Check] only names the submitted value for the handler to verify.
 */
internal sealed interface AuthPasswordDecision {
    data class Check(val password: String) : AuthPasswordDecision
    /** Nothing usable was submitted - describe the (unique) state unchanged. */
    data object Unchanged : AuthPasswordDecision
}

internal object AuthPasswordFlow {

    fun decide(input: AuthPasswordInput): AuthPasswordDecision =
        input.password?.let { AuthPasswordDecision.Check(it) } ?: AuthPasswordDecision.Unchanged

    /** Same derivation for start/patch/read - one place turns the state into `next.step`/`stepData`. */
    fun describe(): Pair<String, StepData> = "auth" to MissingFields(listOf("password"))

    /** The fixed demo password, so a tester never has to remember one - never part of the step. */
    fun demo(): Map<String, Any?> = mapOf("password" to DEMO_PASSWORD)
}
