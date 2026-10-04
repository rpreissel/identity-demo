package com.example.identity.tools.auth_sms.internal.authsms

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_sms.internal.TanGenerator
import java.time.Instant
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.StepData

private const val STEP_AUTH = "auth"
private const val FIELD_TAN = "tan"

/**
 * Pure state of the auth-sms flow (docs/03-tool-architektur.md #6). One shape only: the account is
 * known via the channel, so there is no earlier phase to model.
 */
internal data class AuthSmsState(val issuedTanHash: String, val tanExpiresAt: Instant) {
    val step: String get() = STEP_AUTH
    val missingFields: List<String> get() = listOf(FIELD_TAN)

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, StepData> = step to MissingFields(missingFields)

    companion object {
        /** Turns [AuthSmsToolSession]'s persisted columns back into a [AuthSmsState]. */
        fun of(toolSessionId: ToolSessionId, issuedTanHash: String?, tanExpiresAt: Instant?): AuthSmsState = AuthSmsState(
            checkNotNull(issuedTanHash) { "auth-sms tool data $toolSessionId without issuedTanHash" },
            checkNotNull(tanExpiresAt) { "auth-sms tool data $toolSessionId without tanExpiresAt" }
        )
    }
}

/** What one PATCH submitted. */
internal data class AuthSmsInput(val tan: String? = null)

/** What [AuthSmsFlow.decide] concluded should happen. */
internal sealed interface AuthSmsDecision {
    data object Complete : AuthSmsDecision
    data object WrongTan : AuthSmsDecision
    /** Nothing usable was submitted - describe the (unique) state unchanged. */
    data object Unchanged : AuthSmsDecision
}

internal object AuthSmsFlow {

    fun decide(state: AuthSmsState, input: AuthSmsInput, tanGenerator: TanGenerator): AuthSmsDecision {
        val tan = input.tan ?: return AuthSmsDecision.Unchanged
        return if (tanGenerator.matches(tan, state.issuedTanHash, state.tanExpiresAt)) {
            AuthSmsDecision.Complete
        } else {
            AuthSmsDecision.WrongTan
        }
    }

}
