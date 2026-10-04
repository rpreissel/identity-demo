package com.example.identity.tools.auth_sms.internal.enrollsms

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.values.PhoneNumber
import com.example.identity.tools.auth_sms.internal.TanGenerator
import java.time.Instant
import com.example.identity.tools.auth_sms.api.v1.EnrollSmsStep
import com.example.identity.contract.tool_api.StepData

private const val STEP_ENROLL = "enroll"
private const val STEP_TAN_INPUT = "tanInput"
private const val FIELD_PHONE_NUMBER = "phoneNumber"
private const val FIELD_TAN = "tan"
private const val FIELD_CONSENT = "consent"

/**
 * Pure state of the enroll-sms flow (docs/03-tool-architektur.md #6). Persisted as
 * [EnrollSmsToolSession]'s nullable columns; [Companion.of] turns them back into this type.
 */
internal sealed interface EnrollSmsState {
    /** `next.step` for this position. */
    val step: String
    val missingFields: List<String>

    /**
     * Same derivation for start/patch/read - one place turns this state into `next.step`/`stepData`.
     * [needsConsent]: the consent still has to come with the number (version 2, ADR-51).
     */
    fun describe(replaces: Boolean, needsConsent: Boolean): Pair<String, StepData> =
        step to EnrollSmsStep(if (needsConsent && this is AwaitingPhoneNumber) missingFields + FIELD_CONSENT else missingFields, replaces)

    data object AwaitingPhoneNumber : EnrollSmsState {
        override val step = STEP_ENROLL
        override val missingFields = listOf(FIELD_PHONE_NUMBER)
    }

    data class AwaitingTan(val phoneNumber: String, val issuedTanHash: String, val tanExpiresAt: Instant) : EnrollSmsState {
        override val step = STEP_TAN_INPUT
        override val missingFields = listOf(FIELD_TAN)
    }

    companion object {
        /** Turns [EnrollSmsToolSession]'s persisted, nullable columns back into a [EnrollSmsState]. */
        fun of(toolSessionId: ToolSessionId, phoneNumber: String?, issuedTanHash: String?, tanExpiresAt: Instant?): EnrollSmsState {
            val number = phoneNumber ?: return AwaitingPhoneNumber
            return AwaitingTan(
                number,
                checkNotNull(issuedTanHash) { "enroll-sms tool data $toolSessionId has a phoneNumber but no issuedTanHash" },
                checkNotNull(tanExpiresAt) { "enroll-sms tool data $toolSessionId has a phoneNumber but no tanExpiresAt" }
            )
        }
    }
}

/** What one PATCH submitted - all optional, exactly the API's "only the changed part" rule. */
internal data class EnrollSmsInput(val phoneNumber: String? = null, val tan: String? = null, val consent: Boolean? = null)

/** What [EnrollSmsFlow.decide] concluded should happen. */
internal sealed interface EnrollSmsDecision {
    /**
     * A phone number was submitted. It wins over a TAN in the same call, because a changed number
     * invalidates whatever TAN was pending for the old one.
     */
    data class SendTan(val phoneNumber: String) : EnrollSmsDecision
    data class InvalidPhoneNumber(val raw: String) : EnrollSmsDecision
    /** A number came without the consent version 2 asks for: nothing is sent. */
    data class ConsentMissing(val state: EnrollSmsState) : EnrollSmsDecision
    data class Complete(val phoneNumber: String) : EnrollSmsDecision
    data class WrongTan(val state: EnrollSmsState.AwaitingTan) : EnrollSmsDecision
    /** Nothing usable for the current state - describe it unchanged (start/read, or an empty PATCH). */
    data class Unchanged(val state: EnrollSmsState) : EnrollSmsDecision
}

/**
 * The one place that decides what an enroll-sms step means, instead of three handler methods
 * each re-deriving it from which parameter happens to be non-null.
 */
internal object EnrollSmsFlow {

    /** [needsConsent]: no TAN goes out before the consent (version 2, not yet given in this run). */
    fun decide(state: EnrollSmsState, input: EnrollSmsInput, tanGenerator: TanGenerator, needsConsent: Boolean = false): EnrollSmsDecision {
        input.phoneNumber?.let { raw ->
            if (needsConsent && input.consent != true) return EnrollSmsDecision.ConsentMissing(state)
            val number = PhoneNumber.parse(raw)
            return if (number != null) {
                EnrollSmsDecision.SendTan(number.value)
            } else {
                EnrollSmsDecision.InvalidPhoneNumber(raw)
            }
        }
        return when (state) {
            is EnrollSmsState.AwaitingPhoneNumber -> EnrollSmsDecision.Unchanged(state)
            is EnrollSmsState.AwaitingTan -> {
                val tan = input.tan ?: return EnrollSmsDecision.Unchanged(state)
                if (tanGenerator.matches(tan, state.issuedTanHash, state.tanExpiresAt)) {
                    EnrollSmsDecision.Complete(state.phoneNumber)
                } else {
                    EnrollSmsDecision.WrongTan(state)
                }
            }
        }
    }

}
