package com.example.identity.tools.auth_sms.internal.authsmslookup

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_sms.internal.TanGenerator
import java.time.Instant
import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.StepData

private const val STEP_AUTH = "auth"
private const val STEP_TAN_INPUT = "tanInput"
private const val FIELD_EMAIL = "email"
private const val FIELD_TAN = "tan"

/**
 * Pure state of the auth-sms-lookup flow (docs/03-tool-architektur.md #6). The controller resolves
 * the email; this only models what the result means for the flow's position.
 */
internal sealed interface AuthSmsLookupState {
    /** `next.step` for this position. */
    val step: String
    val missingFields: List<String>
    /** Demo-only values for this step - never part of the step data (docs/05-api.md, `demo`). */
    val demo: Map<String, Any?> get() = emptyMap()

    /** Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`. */
    fun describe(): Pair<String, StepData> = step to MissingFields(missingFields)

    data object AwaitingEmail : AuthSmsLookupState {
        override val step = STEP_AUTH
        override val missingFields = listOf(FIELD_EMAIL)
    }

    /** [accountId] is null without an active sms method; the state looks the same, the TAN check fails. */
    data class AwaitingTan(val accountId: AccountId?, val issuedTanHash: String, val tanExpiresAt: Instant) : AuthSmsLookupState {
        override val step = STEP_TAN_INPUT
        override val missingFields = listOf(FIELD_TAN)
    }

    companion object {
        /** Turns [AuthSmsLookupToolSession]'s persisted, nullable columns back into a [AuthSmsLookupState]. */
        fun of(toolSessionId: ToolSessionId, accountId: AccountId?, issuedTanHash: String?, tanExpiresAt: Instant?): AuthSmsLookupState {
            val hash = issuedTanHash ?: return AwaitingEmail
            return AwaitingTan(
                accountId,
                hash,
                checkNotNull(tanExpiresAt) { "auth-sms-lookup tool data $toolSessionId has issuedTanHash but no tanExpiresAt" }
            )
        }
    }
}

internal sealed interface AuthSmsLookupDecision {
    data class Complete(val accountId: AccountId) : AuthSmsLookupDecision
    data class WrongTan(val accountId: AccountId?) : AuthSmsLookupDecision
    /** Nothing usable for the current state, e.g. an empty PATCH or a tan before any email. */
    data class Unchanged(val state: AuthSmsLookupState) : AuthSmsLookupDecision
}

internal object AuthSmsLookupFlow {

    fun decideTan(state: AuthSmsLookupState, tan: String?, tanGenerator: TanGenerator): AuthSmsLookupDecision {
        if (state !is AuthSmsLookupState.AwaitingTan) return AuthSmsLookupDecision.Unchanged(state)
        val value = tan ?: return AuthSmsLookupDecision.Unchanged(state)
        return if (state.accountId != null && tanGenerator.matches(value, state.issuedTanHash, state.tanExpiresAt)) {
            AuthSmsLookupDecision.Complete(state.accountId)
        } else {
            AuthSmsLookupDecision.WrongTan(state.accountId)
        }
    }

}
