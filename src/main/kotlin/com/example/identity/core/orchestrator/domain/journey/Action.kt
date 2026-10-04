package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.core.orchestrator.domain.AuthIntent

/**
 * A named side effect a strategy decided, wrapped in a [Transition.Perform] for `JourneyService` to
 * execute; the strategy never acts itself. Some variants carry what a just-completed tool
 * established ([JourneyEvent.Completed]), the rest are a strategy's own actions.
 *
 * Only [RecordIdentification] and [AdoptAttestation] can land on an account other than the one
 * bound to the channel, and both go through the same gate in `JourneyActionExecutor`. Which
 * account a resolution may land on is a property of the evidence, not of the caller. Every other
 * variant acts on the account already known from context and carries no account id.
 */
sealed interface Action {
    /**
     * A completed identification (e.g. `ident-eid`) or correlation (`ident-kvnr`) tool resolved or
     * extended an identity. Whether an account is already in hand is read at execution time, not
     * decided by the caller, so no strategy can skip the merge check.
     */
    data class RecordIdentification(val tool: Tool, val outcome: ToolOutcome.Completed.Identified) : Action

    /**
     * An attribute the account owns was attested (e.g. a confirmed email address): record the claims
     * and the anchor, but create no method instance and bind no device (docs/12-entscheidungen.md,
     * `AttributeType.authority`). It may only land on a different existing account after an
     * identification ([checkAttestationMove]): owning a mailbox does not prove who owns the account.
     */
    data class AdoptAttestation(val tool: Tool, val outcome: ToolOutcome.Completed.Attested) : Action

    /**
     * A new credential was enrolled. Whether this also links the device is not a field here; it
     * follows from [AuthIntent.bindsDeviceImplicitly] and, for a Web channel, from there being
     * no device. [admittedAt]: the session's level when the journey admitted this enrollment, for a
     * change in place. The credential is then written under at least that level, even if the
     * session's proofs aged while the user typed; a change never lowers a credential.
     */
    data class AdoptCredential(
        val tool: Tool,
        val outcome: ToolOutcome.Completed.Enrolled,
        val admittedAt: AcrLevel? = null
    ) : Action

    /**
     * A credential was proven. Whether the tool may name the account it proved is not a field here:
     * it is derived at execution time from the tool's role and the live binding ([accountOfProof]).
     * That safety rule must not rest on every caller passing the right flag.
     */
    data class AcceptProof(
        val tool: Tool,
        val outcome: ToolOutcome.Completed.Authenticated
    ) : Action

    /**
     * Prime a fresh channel's evidence from [methods] before the journey's first decision
     * (`JourneyService.start`'s `seedAction`, docs/04-orchestrierung.md, "RestoreData als erster
     * Übergang"). No strategy sees this action; it runs before `initialState()`.
     */
    data class ApplyRestoredEvidence(
        /**
         * Which foreign system vouches for [methods] (e.g. `AmrSource.KEYCLOAK`). Evidence is merged
         * per source, so a later report from the same source replaces its own set.
         */
        val source: String,
        /**
         * The complete current set from [source], not a delta: every caller re-reports everything
         * it knows on every call (docs/05-api.md Abschnitt 3).
         */
        val methods: List<MethodEvidence>
    ) : Action

    /**
     * A tool approved a peer channel's pending request. The tool already wrote the approval itself
     * (e.g. `QrLoginRequest`); this action only records the outcome like any other tool outcome, for
     * auditing. This channel's own evidence does not change.
     */
    data class RecordApproval(val tool: Tool, val outcome: ToolOutcome.Completed.Approved) : Action

    /**
     * Revoke one authentication method of the account this session holds. The credential itself
     * goes, not just an active flag. The executor rejects self-lockout. Names the method, not the
     * account: the owner is read from the live session, so a stale id cannot reach into another
     * account's methods.
     */
    data class RevokeAuthMethod(val methodInstanceId: String) : Action

    /**
     * Withdraw one account attribute, e.g. a confirmed address: an account-owned fact, not a
     * credential. Methods that depended on it fall as a consequence, worked out at execution time
     * from the catalog's `requires` declarations ([MethodDependencies]).
     */
    data class RetractAttribute(val attributeType: AttributeType) : Action

    /**
     * Link the current device to the account this session holds, after an accepted device-binding
     * offer ([JourneyEvent.Answered]). Carries no account id: one stored in the state would be read
     * back only when the user answers. A device link outlives every journey and sends the next
     * `FAST_ACCESS` straight into that account, so it must follow the session's current binding.
     */
    data object LinkDevice : Action

    /**
     * Delete the account this session holds, and everything it owns. Irreversible, so
     * `JourneyActionExecutor` re-checks [requiredAcr] against the current evidence right before
     * executing it. Carries no account id, so the permission check and the deletion cannot name
     * different accounts.
     */
    data object DeleteAccount : Action {
        /**
         * The ACR re-checked right before execution. It lives here so the generic machine does not
         * import a concrete strategy. It is exactly [selfServiceAcrFloor], not a second definition.
         */
        fun requiredAcr(account: AccountProfile?): AcrLevel = selfServiceAcrFloor(account)
    }
}
