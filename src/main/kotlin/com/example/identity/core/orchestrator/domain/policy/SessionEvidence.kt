package com.example.identity.core.orchestrator.domain.policy

import com.example.identity.core.orchestrator.domain.AmrSource
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolDescriptor
import java.time.Instant

/**
 * An auth method identifier ("sms", "password", "eid", ...). Not an enum: every module declares its
 * own methods (docs/03-tool-architektur.md #1). A distinct type so it cannot be mixed up with an
 * [AcrLevel] or other string.
 */
@JvmInline
value class MethodName(val value: String) {
    override fun toString(): String = value
}

/**
 * Which trust question a [MethodEvidence] entry answers: IAL ("who is this?", identification) or
 * AAL ("is this the same person, proven again right now?", enrollment and auth). An identification
 * is a one-time event, not a factor presented at login, so it never joins an MFA bump
 * (docs/04-orchestrierung.md #8).
 */
enum class EvidenceAxis {
    IDENTITY,
    AUTHENTICATOR
}

/**
 * Which assurance axis a completed run of this tool raises, or `null` for neither. For `null` no
 * [MethodEvidence] is recorded, so such a tool cannot move an ACR.
 *
 * Exhaustive over [ToolRole] on purpose, so a new role must state its axis. An identification and a
 * [ToolRole.CORRELATION] step look alike, but only the identification raises the IAL.
 */
fun ToolDescriptor.evidenceAxis(): EvidenceAxis? = when (role) {
    // Proves who the subject is - the only role that may raise the IAL.
    ToolRole.IDENTIFICATION -> EvidenceAxis.IDENTITY
    // Attaches an already attested identity to a person record and proves nothing itself.
    // Typing a semi-public number must not buy assurance.
    ToolRole.CORRELATION -> null
    // Evidence about the authenticator - the AAL side.
    ToolRole.ENROLLMENT, ToolRole.KNOWN_ACCOUNT_AUTH, ToolRole.ACCOUNT_LOOKUP_AUTH -> EvidenceAxis.AUTHENTICATOR
    // Decides another channel's pending request; says nothing about this one.
    ToolRole.PEER_APPROVAL -> null
    // `confirm-email` proves mailbox access like `auth-email`, but for an address still being
    // claimed. A self-chosen address must not raise the level of the account it creates.
    // `ToolOutcome.Completed.Attested` has an empty `amr` for the same reason.
    ToolRole.ATTESTATION -> null
}

/** One proven method's evidence. One record per method, so no parallel maps can drift apart. */
data class MethodEvidence(
    val method: MethodName,
    /** This method's own loa, the base [AuthPolicy.resolveAcr] prices from. */
    val loa: AcrLevel,
    /**
     * This method's ceiling for an MFA combination (docs/06-ablaeufe.md #1), from its enrollment
     * record. For a method Keycloak reported natively the caller supplies it (docs/05-api.md
     * Abschnitt 3). Null contributes nothing to the cap.
     */
    val enrolledUnderAcr: AcrLevel? = null,
    /** The factor kinds this method contributes. */
    val factorTypes: Set<FactorType> = emptySet(),
    /**
     * [AmrSource.ORCHESTRATOR] or [AmrSource.KEYCLOAK]. [AuthPolicy] does not read it. Carried here
     * so it survives a `RestoreData` round-trip; otherwise a native Keycloak report could later
     * downgrade a method an orchestrator tool proved.
     */
    val source: String,
    /**
     * What produced this proof: an orchestrator tool's `toolId`, Keycloak's authenticator id
     * (docs/05-api.md Abschnitt 3), or `"simulation"` for a candidate the policy projects.
     */
    val amrSourceId: String,
    /** Which trust question this entry answers; only an identification sets [EvidenceAxis.IDENTITY]. */
    val axis: EvidenceAxis = EvidenceAxis.AUTHENTICATOR,
    /**
     * When this method was proven. Above loa1 only recent proofs count ([AuthPolicy]); `null` is a
     * proof of unknown age and counts only up to loa1. Recording a proof stamps it with the time.
     */
    val provenAt: Instant? = null,
)

/**
 * What this session has already proven (docs/04-orchestrierung.md #8). Source-agnostic: whether an
 * orchestrator tool or Keycloak proved a method (docs/05-api.md Abschnitt 3) does not matter to
 * [AuthPolicy]. The caller has already resolved each method's loa and factor types.
 */
data class SessionEvidence(
    val methods: List<MethodEvidence>,
) {
    /** Every method proven so far. */
    val amr: List<MethodName> get() = methods.map { it.method }

    /** The union of every proven method's [MethodEvidence.factorTypes]. */
    val factorTypes: Set<FactorType> get() = methods.flatMap { it.factorTypes }.toSet()

    fun acrFor(method: MethodName): AcrLevel? = methods.find { it.method == method }?.loa

    companion object {
        /**
         * Builds evidence from an amr list plus method-keyed maps (the shape `AppTokenSession`
         * persists). [factorTypes] is attached to every method in [amr]; call once per method for
         * exact attribution. The union [SessionEvidence.factorTypes] is the same either way.
         */
        fun from(
            amr: List<String>,
            factorTypes: Set<FactorType>,
            methodAcr: Map<String, String> = emptyMap(),
            enrolledUnderAcr: Map<String, String> = emptyMap(),
            source: Map<String, String> = emptyMap(),
            amrSourceId: Map<String, String> = emptyMap(),
            axis: Map<String, EvidenceAxis> = emptyMap(),
        ): SessionEvidence = SessionEvidence(
            amr.distinct().map { m ->
                MethodEvidence(
                    MethodName(m),
                    methodAcr[m]?.let(AcrLevel::parse) ?: AcrLevel.NONE,
                    enrolledUnderAcr[m]?.let(AcrLevel::parse),
                    factorTypes,
                    // Default for callers without an opinion; AuthPolicy does not read it.
                    source[m] ?: AmrSource.ORCHESTRATOR,
                    // Falls back to the method name when the caller has no source id.
                    amrSourceId[m] ?: m,
                    // An identification (e.g. "fsc") must say so explicitly.
                    axis[m] ?: EvidenceAxis.AUTHENTICATOR,
                )
            },
        )
    }
}
