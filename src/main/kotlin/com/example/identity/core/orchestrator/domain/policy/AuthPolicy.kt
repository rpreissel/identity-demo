package com.example.identity.core.orchestrator.domain.policy

import com.example.identity.core.orchestrator.domain.policy.DefaultAuthPolicy
import com.example.identity.core.account.AccountProfile
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolId

/** The only place that knows what a *combination* of evidence means (docs/04-orchestrierung.md #8). */
interface AuthPolicy {
    /**
     * Does what this session has proven so far satisfy [requiredAcr]? Pass [account] null only when
     * none is resolvable yet.
     */
    fun isSatisfied(evidence: AuthEvidence, requiredAcr: AcrLevel, account: AccountProfile?): Boolean

    /**
     * Which of the account's AUTH tools could close the remaining gap right now? A key-bound method
     * is offered only on the device that holds its credential ([CandidateContext.bindingKeyRef])
     * and while that device is linked to this account ([CandidateContext.linkedAccountId],
     * docs/09-dpop.md). [CandidateContext.availableTools] narrows to what the channel can present;
     * `null` means no filter.
     */
    fun authCandidates(ctx: CandidateContext): List<ToolId>

    /**
     * Which IDENT tools (re-identification, e.g. ident-fsc) could also close the remaining gap?
     * Separate from [authCandidates]: an identification's maxAcr does not depend on the account's
     * enrollments, and only callers that want re-identification as a fallback ask for it.
     */
    fun reIdentCandidates(ctx: CandidateContext): List<ToolId>

    /**
     * Could this account reach [requiredAcr] in a future login with its current enrollments, and if
     * not, why? The reason is a structured [UnreachableReason]; the caller turns it into text
     * (`orchestrator.journey.AbortMessages`).
     */
    fun reachability(account: AccountProfile, requiredAcr: AcrLevel): Reachability

    /** Which ENROLL tools would close the gap toward requiredAcr? */
    fun enrollmentCandidates(ctx: CandidateContext): List<ToolId>

    /** Level implied by the given evidence (IAL and AAL, docs/04-orchestrierung.md #8). */
    fun resolveAcr(evidence: AuthEvidence, account: AccountProfile?): AcrLevel
}

/** Shared context for candidate resolution methods. */
data class CandidateContext(
    val evidence: AuthEvidence,
    val requiredAcr: AcrLevel,
    val account: AccountProfile? = null,
    val bindingKeyRef: String? = null,
    val linkedAccountId: Long? = null,
    val availableTools: Set<ToolId>? = null
)

/** Result of [AuthPolicy.reachability]; not a `Boolean`, so a caller cannot ignore the reason. */
sealed interface Reachability {
    /** requiredAcr is reachable with the account's current standing methods. */
    data object Reachable : Reachability

    /** requiredAcr is not reachable; [reason] says why, for the caller to render. */
    data class NotReachable(val reason: UnreachableReason) : Reachability
}

/**
 * Why [Reachability.NotReachable], as a domain fact. The policy names what is missing; the caller
 * decides how to say it and in which language.
 */
sealed interface UnreachableReason {
    /** No active authentication method at all. */
    data object NoActiveMethod : UnreachableReason

    /** The active methods cover fewer than 2 factor types; [methods]/[factorTypes] are what is active. */
    data class SingleFactorType(val methods: List<String>, val factorTypes: Set<FactorType>) : UnreachableReason

    /**
     * Level and factor types would suffice, but every combining method was enrolled under a lower
     * ACR than required. [maxEnrolledUnderAcr] is the highest any of them reached.
     */
    data class CombinationCapped(val maxEnrolledUnderAcr: AcrLevel) : UnreachableReason

    /** Same as [CombinationCapped], for a single method. */
    data class SingleMethodCapped(val method: String, val maxEnrolledUnderAcr: AcrLevel) : UnreachableReason
}
