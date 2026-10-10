package com.example.identity.tools.auth_invite.internal

import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.ToolStep

/** What one PATCH of auth-invite-lookup carries. The number is either the KVNR or the Partnernummer. */
internal data class AuthInviteInput(val kvnr: String? = null, val partnerNumber: String? = null, val code: String? = null)

internal sealed interface AuthInviteDecision {
    data class Check(val code: String) : AuthInviteDecision
    data class Incomplete(val missingFields: List<String>) : AuthInviteDecision
}

/**
 * Single-shot flow: number and one-time password arrive and are checked together, so nothing is
 * staged between calls. `kvnr` stands for "a number" and is missing only when neither is given,
 * as in `ident-fsc`.
 */
internal object AuthInviteLookupFlow {

    val ALL_FIELDS = listOf("kvnr", "code")

    fun decide(input: AuthInviteInput): AuthInviteDecision {
        val missing = buildList {
            if (input.kvnr.isNullOrBlank() && input.partnerNumber.isNullOrBlank()) add("kvnr")
            if (input.code.isNullOrBlank()) add("code")
        }
        return if (missing.isEmpty()) AuthInviteDecision.Check(input.code!!) else AuthInviteDecision.Incomplete(missing)
    }

    fun describe(missingFields: List<String> = ALL_FIELDS): ToolStep = ToolStep("auth", MissingFields(missingFields))
}
