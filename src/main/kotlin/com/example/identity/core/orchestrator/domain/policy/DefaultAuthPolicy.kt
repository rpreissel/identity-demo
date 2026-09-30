package com.example.identity.core.orchestrator.domain.policy

import com.example.identity.core.orchestrator.domain.policy.requiresSatisfied
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.policy.CandidateContext
import com.example.identity.core.orchestrator.domain.policy.EvidenceAxis
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.example.identity.core.orchestrator.domain.policy.Reachability
import com.example.identity.core.orchestrator.domain.policy.UnreachableReason
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.domain.ToolCatalog
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.ClaimTrust
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolDescriptor
import com.example.identity.contract.tool_api.ToolId
import java.time.Clock
import java.time.Duration

/**
 * Default policy (docs/04-orchestrierung.md #8). Each tool's maxAcr gives its own level. Two AUTH
 * methods of different factor types, proven together, earn one tier more, capped by the highest
 * level either was enrolled under (ADR-5). Otherwise a low-trust session could add two weak factors
 * and escalate past anything ever proven. `resolveAcr` is `max(IAL, AAL)`, computed separately so
 * that an identification never combines with an unrelated auth factor into a false MFA bump.
 *
 * A level above loa1 ages: only proofs younger than [loa2MaxAge] count toward it, older ones
 * still carry loa1 (docs/04-orchestrierung.md #8, like Keycloak's `loa-max-age`).
 */
class DefaultAuthPolicy(
    private val toolRegistry: ToolCatalog,
    private val clock: Clock,
    private val loa2MaxAge: Duration = DEFAULT_LOA2_MAX_AGE,
) : AuthPolicy {

    override fun resolveAcr(evidence: SessionEvidence, account: AccountProfile?): AcrLevel =
        AcrLevel.max(AcrLevel.min(levelOf(evidence), AGELESS_CEILING), levelOf(recent(evidence)))

    private fun levelOf(evidence: SessionEvidence): AcrLevel =
        AcrLevel.max(identityAssuranceLevel(evidence), authenticatorAssuranceLevel(evidence))

    /** The proofs that still count above loa1. */
    private fun recent(evidence: SessionEvidence): SessionEvidence {
        val since = clock.instant().minus(loa2MaxAge)
        return SessionEvidence(evidence.methods.filter { it.provenAt?.isBefore(since) == false })
    }

    /** The proofs that count toward [requiredAcr]: all of them up to loa1, only recent ones above. */
    private fun countingToward(evidence: SessionEvidence, requiredAcr: AcrLevel): SessionEvidence =
        if (AcrLevel.rank(requiredAcr) > AcrLevel.rank(AGELESS_CEILING)) recent(evidence) else evidence

    /**
     * IAL: the highest loa any identification has established in this session. An identification
     * from a past session does not count; it acts only through `enrolledUnderAcr` (ADR-5).
     */
    private fun identityAssuranceLevel(evidence: SessionEvidence): AcrLevel {
        val reachable = evidence.methods.filter { it.axis == EvidenceAxis.IDENTITY }
            .maxOfOrNull { AcrLevel.rank(it.loa) } ?: return AcrLevel.NONE
        return AcrLevel.levelAt(reachable)
    }

    /** AAL: [baseAcr] plus [applyMfaBump], over [EvidenceAxis.AUTHENTICATOR] entries only. */
    private fun authenticatorAssuranceLevel(evidence: SessionEvidence): AcrLevel {
        val authenticatorFactors = evidence.methods.filter { it.axis == EvidenceAxis.AUTHENTICATOR }
        return applyMfaBump(baseAcr(authenticatorFactors), SessionEvidence(authenticatorFactors))
    }

    override fun isSatisfied(evidence: SessionEvidence, requiredAcr: AcrLevel, account: AccountProfile?): Boolean {
        val counting = countingToward(evidence, requiredAcr)
        val levelOk = AcrLevel.rank(levelOf(counting)) >= AcrLevel.rank(requiredAcr)
        // Checked per axis, not as one union: a tool covering two factor types on its own axis is
        // MFA (e.g. ident-eid: card + PIN), but identity and auth factors never combine.
        val identityFactorTypes = counting.methods.filter { it.axis == EvidenceAxis.IDENTITY }.flatMap { it.factorTypes }.toSet()
        val authenticatorFactorTypes = counting.methods.filter { it.axis == EvidenceAxis.AUTHENTICATOR }.flatMap { it.factorTypes }.toSet()
        val mfaOk = !requiresMfa(requiredAcr) || identityFactorTypes.size >= 2 || authenticatorFactorTypes.size >= 2
        return levelOk && mfaOk
    }

    override fun reachability(account: AccountProfile, requiredAcr: AcrLevel): Reachability {
        val active = account.authenticationMethods.filter { it.active }
        if (active.isEmpty()) return Reachability.NotReachable(UnreachableReason.NoActiveMethod)

        val descriptors = active.mapNotNull { m -> descriptorFor(m.method)?.let { m to it } }
        val factorTypesUnion = descriptors.flatMap { it.second.factorTypes }.toSet()
        val distinctMethods = descriptors.map { it.second.method }.distinct().size
        val bestAcr = descriptors
            .map { (m, d) -> AcrLevel.min(AcrLevel.of(m.enrolledUnderAcr), d.maxAcr) }
            .maxByOrNull { AcrLevel.rank(it) }
            ?: AcrLevel.NONE
        val maxEnrolledUnderAcr = active.maxOfOrNull { AcrLevel.rank(AcrLevel.of(it.enrolledUnderAcr)) }?.let { AcrLevel.levelAt(it) } ?: AcrLevel.NONE
        val effectiveAcr = combinedAcr(bestAcr, distinctMethods, factorTypesUnion, maxEnrolledUnderAcr)

        val levelOk = AcrLevel.rank(effectiveAcr) >= AcrLevel.rank(requiredAcr)
        val mfaOk = !requiresMfa(requiredAcr) || factorTypesUnion.size >= 2
        if (levelOk && mfaOk) return Reachability.Reachable

        // Factor-type coverage alone, not distinctMethods: a single method covering two factor
        // types (e.g. `device`) is not missing "a method of another factor type". Its limit is
        // the enrolledUnderAcr cap below.
        if (factorTypesUnion.size < 2) {
            val methodNames = descriptors.map { it.second.method }.distinct()
            return Reachability.NotReachable(UnreachableReason.SingleFactorType(methodNames, factorTypesUnion))
        }

        val cap = maxEnrolledUnderAcr
        return Reachability.NotReachable(
            if (distinctMethods >= 2) UnreachableReason.CombinationCapped(cap)
            else UnreachableReason.SingleMethodCapped(descriptors.first().second.method, cap)
        )
    }

    override fun enrollmentCandidates(ctx: CandidateContext): List<ToolId> {
        val account = checkNotNull(ctx.account) { "enrollmentCandidates requires an account in CandidateContext" }
        val activeMethods = account.authenticationMethods.filter { it.active }.map { it.method }.toSet()
        return toolRegistry.descriptors()
            .filter { it.role == ToolRole.ENROLLMENT }
            // Singleton methods disappear once active; multi-instance methods (device) stay, so a
            // new device can add its own instance.
            .filter { it.method !in activeMethods || it.allowsMultipleInstances }
            .filter { it.requires.all { requirement -> requiresSatisfied(requirement, account) } }
            .map { it.toolId }
    }

    override fun authCandidates(ctx: CandidateContext): List<ToolId> {
        val requiredAcr = ctx.requiredAcr
        // An aged proof neither counts nor blocks its method from being offered again.
        val evidence = countingToward(ctx.evidence, requiredAcr)
        val account = checkNotNull(ctx.account) { "authCandidates requires an account in CandidateContext" }
        val bindingKeyRef = ctx.bindingKeyRef
        val linkedAccountId = ctx.linkedAccountId
        val availableTools = ctx.availableTools
        val usedMethods = evidence.methods.map { it.method.value }.toSet()
        val active = account.authenticationMethods.filter { it.active }

        // With a known account, only KNOWN_ACCOUNT_AUTH tools, never an ACCOUNT_LOOKUP_AUTH sibling that
        // resolves the account itself (docs/03-tool-architektur.md).
        //
        // Filtered to what is offerable here before computing singleMethodSuffices. A method that
        // cannot be offered on this channel (e.g. `auth-qr` in the App) must not count as "one
        // method alone suffices", or it would suppress the two-factor fallback for the others.
        val eligible = active.mapNotNull { m ->
            val descriptor = toolRegistry.descriptors()
                .firstOrNull { it.role == ToolRole.KNOWN_ACCOUNT_AUTH && it.method == m.method }
                ?: return@mapNotNull null
            if (availableTools != null && descriptor.toolId !in availableTools) return@mapNotNull null
            // A key-bound method only on the device holding its credential, and only while that
            // device is linked to this account; anything else would fail for sure (docs/09-dpop.md).
            if (!descriptor.usableByCaller(m.details, bindingKeyRef, linkedAccountId, account.accountId)) {
                return@mapNotNull null
            }
            m to descriptor
        }

        // Below loa3, MFA is needed when no single offerable method's capped level reaches
        // requiredAcr. Then every method adding a factor type not yet proven is worth offering.
        val singleMethodSuffices = eligible.any { (m, descriptor) ->
            AcrLevel.rank(AcrLevel.min(AcrLevel.of(m.enrolledUnderAcr), descriptor.maxAcr)) >= AcrLevel.rank(requiredAcr)
        }

        return eligible.filter { (m, _) -> m.method !in usedMethods }.mapNotNull { (m, descriptor) ->
            val cappedAcr = AcrLevel.min(AcrLevel.of(m.enrolledUnderAcr), descriptor.maxAcr)
            // The evidence as it would be if this candidate were also proven.
            val projected = SessionEvidence(
                evidence.methods + MethodEvidence(
                    MethodName(m.method), cappedAcr, m.enrolledUnderAcr?.let(AcrLevel::of), descriptor.factorTypes,
                    source = "simulation", amrSourceId = "simulation", provenAt = clock.instant()
                ),
            )
            val projectedAcr = applyMfaBump(baseAcr(projected.methods), projected)
            val helpsLevel = AcrLevel.rank(projectedAcr) >= AcrLevel.rank(requiredAcr)
            val helpsMfa = !singleMethodSuffices && (descriptor.factorTypes - evidence.factorTypes).isNotEmpty()

            descriptor.toolId.takeIf { helpsLevel || helpsMfa }
        }.distinct() // several instances of one method (devices) offer its AUTH tool once
    }

    override fun reIdentCandidates(ctx: CandidateContext): List<ToolId> {
        val requiredAcr = ctx.requiredAcr
        val evidence = countingToward(ctx.evidence, requiredAcr)
        val usedMethods = evidence.methods.map { it.method.value }.toSet()
        // An identification's amr need not be its method name (ident-nect reports
        // `nect-<procedure>`), so "already used" also checks which tool produced the evidence.
        val usedTools = evidence.methods.map { it.amrSourceId }.toSet()
        return toolRegistry.descriptors()
            // Role, not category: a CORRELATION step is no fresh proof of identity (ADR-18).
            .filter { it.role == ToolRole.IDENTIFICATION }
            .filter { it.method !in usedMethods && it.toolId.value !in usedTools }
            .filter { AcrLevel.rank(it.maxAcr) >= AcrLevel.rank(requiredAcr) }
            // Same precondition gate as every other candidate path.
            .filter { descriptor -> descriptor.requires.all { requiresSatisfied(it, ctx.account) } }
            .map { it.toolId }
    }

    /** Highest loa among [factors]' own per-method claims - see [MethodEvidence.loa]. */
    private fun baseAcr(factors: List<MethodEvidence>): AcrLevel {
        val reachable = factors.maxOfOrNull { AcrLevel.rank(it.loa) } ?: return AcrLevel.NONE
        return AcrLevel.levelAt(reachable)
    }

    /**
     * The MFA bump over one session's evidence, capped by the highest [MethodEvidence.enrolledUnderAcr]
     * among it. Does not filter by axis: the caller must pass authenticator evidence only, or an
     * identification could buy MFA credit it already priced into its own loa. A method without
     * `enrolledUnderAcr` adds nothing to the cap (docs/05-api.md Abschnitt 3).
     */
    private fun applyMfaBump(base: AcrLevel, evidence: SessionEvidence): AcrLevel {
        val distinctMethods = evidence.methods.map { it.method }.distinct().size
        val maxEnrolledUnderAcr = evidence.methods.mapNotNull { it.enrolledUnderAcr }
            .maxOfOrNull { AcrLevel.rank(it) }
            ?.let { AcrLevel.levelAt(it) }
            ?: AcrLevel.NONE
        return combinedAcr(base, distinctMethods, evidence.factorTypes, maxEnrolledUnderAcr)
    }

    /**
     * The combination rule: two or more methods covering two or more factor types earn one tier
     * above [base], capped by [maxEnrolledUnderAcr] and by [NIST_COMBINATION_CEILING].
     *
     * The ceiling exists because NIST 800-63B allows combining single-factor authenticators only
     * up to AAL2. AAL3 needs a specific authenticator technology, not two strong things combined.
     */
    private fun combinedAcr(base: AcrLevel, distinctMethods: Int, factorTypesUnion: Set<FactorType>, maxEnrolledUnderAcr: AcrLevel): AcrLevel {
        if (distinctMethods < 2 || factorTypesUnion.size < 2) return base
        val bumped = AcrLevel.min(AcrLevels.bump(base), maxEnrolledUnderAcr)
        return AcrLevel.max(base, AcrLevel.min(bumped, NIST_COMBINATION_CEILING))
    }

    /** What an enrolled method gives when proven: its KNOWN_ACCOUNT_AUTH procedure, not whichever comes first. */
    private fun descriptorFor(method: String): ToolDescriptor? = toolRegistry.descriptorOf(method, ToolRole.KNOWN_ACCOUNT_AUTH)

    private fun requiresMfa(requiredAcr: AcrLevel) = AcrLevel.rank(requiredAcr) >= AcrLevel.rank(MFA_FROM_ACR)

    companion object {
        /** Keycloak's `loa-max-age` for LoA 2 (keycloak-migrations V5). */
        val DEFAULT_LOA2_MAX_AGE: Duration = Duration.ofMinutes(30)

        /** The highest level an aged proof still carries. */
        private val AGELESS_CEILING = AcrLevel.LOA1

        private val MFA_FROM_ACR = AcrLevel.LOA3

        /** The highest level the combination bump may produce (see [combinedAcr]). */
        private val NIST_COMBINATION_CEILING = AcrLevel.LOA2
    }
}
