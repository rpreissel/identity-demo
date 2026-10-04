package com.example.identity.core.orchestrator.domain.policy

import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.domain.ToolCatalog
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolId
import java.time.Clock
import java.time.Duration

/**
 * Default policy (docs/04-orchestrierung.md #4). Each tool's maxAcr gives its own level. Two AUTH
 * methods of different factor types, proven together, earn one tier more, capped by the highest
 * level either was enrolled under (ADR-5). Otherwise a low-trust session could add two weak factors
 * and escalate past anything ever proven. `resolveAcr` is `max(IAL, AAL)`, computed separately so
 * that an identification never combines with an unrelated auth factor into a false MFA bump.
 *
 * A level above loa1 ages: only proofs younger than [loa2MaxAge] count toward it, older ones
 * still carry loa1 (docs/04-orchestrierung.md #4, like Keycloak's `loa-max-age`). Self-service on
 * the account asks for a proof younger than [selfServiceMaxAge], whatever its level.
 */
class DefaultAuthPolicy(
    private val toolRegistry: ToolCatalog,
    private val clock: Clock,
    private val loa2MaxAge: Duration = DEFAULT_LOA2_MAX_AGE,
    private val selfServiceMaxAge: Duration = DEFAULT_SELF_SERVICE_MAX_AGE,
) : AuthPolicy {

    override fun hasFreshProof(evidence: SessionEvidence): Boolean {
        val since = clock.instant().minus(selfServiceMaxAge)
        return evidence.methods.any { it.provenAt?.isBefore(since) == false }
    }

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
        if (requiredAcr > AGELESS_CEILING) recent(evidence) else evidence

    /**
     * IAL: the highest loa any identification has established in this session. An identification
     * from a past session does not count; it acts only through `enrolledUnderAcr` (ADR-5).
     */
    private fun identityAssuranceLevel(evidence: SessionEvidence): AcrLevel =
        evidence.methods.filter { it.axis == EvidenceAxis.IDENTITY }.maxOfOrNull { it.loa } ?: AcrLevel.NONE

    /** AAL: [baseAcr] plus [applyMfaBump], over [EvidenceAxis.AUTHENTICATOR] entries only. */
    private fun authenticatorAssuranceLevel(evidence: SessionEvidence): AcrLevel {
        val authenticatorFactors = evidence.methods.filter { it.axis == EvidenceAxis.AUTHENTICATOR }
        return applyMfaBump(baseAcr(authenticatorFactors), SessionEvidence(authenticatorFactors))
    }

    override fun isSatisfied(evidence: SessionEvidence, requiredAcr: AcrLevel, account: AccountProfile?): Boolean {
        val counting = countingToward(evidence, requiredAcr)
        val levelOk = levelOf(counting) >= requiredAcr
        // Checked per axis, not as one union: a tool covering two factor types on its own axis is
        // MFA (e.g. ident-eid: card + PIN), but identity and auth factors never combine.
        fun factorTypesOn(axis: EvidenceAxis) = counting.methods.filter { it.axis == axis }.flatMap { it.factorTypes }.toSet()
        val mfaOk = !requiresMfa(requiredAcr) || EvidenceAxis.entries.any { factorTypesOn(it).size >= 2 }
        return levelOk && mfaOk
    }

    override fun reachability(account: AccountProfile, requiredAcr: AcrLevel): Reachability {
        val active = account.activeAuthenticationMethods
        if (active.isEmpty()) return Reachability.NotReachable(UnreachableReason.NoActiveMethod)

        val provable = active.mapNotNull { m -> descriptorFor(m.method)?.let { m to it } }
        val methodNames = provable.map { (_, d) -> d.method }.distinct()
        val factorTypesUnion = provable.flatMap { (_, d) -> d.factorTypes }.toSet()
        val bestAcr = provable.maxOfOrNull { (m, d) -> cappedAcr(m, d) } ?: AcrLevel.NONE
        val maxEnrolledUnderAcr = active.mapNotNull { AcrLevel.parse(it.enrolledUnderAcr) }.maxOrNull() ?: AcrLevel.NONE
        val effectiveAcr = combinedAcr(bestAcr, methodNames.size, factorTypesUnion, maxEnrolledUnderAcr)

        val levelOk = effectiveAcr >= requiredAcr
        val mfaOk = !requiresMfa(requiredAcr) || factorTypesUnion.size >= 2
        if (levelOk && mfaOk) return Reachability.Reachable

        // Factor-type coverage alone, not the number of methods: a single method covering two
        // factor types (e.g. `device`) is not missing "a method of another factor type". Its limit
        // is the enrolledUnderAcr cap below.
        if (factorTypesUnion.size < 2) {
            return Reachability.NotReachable(UnreachableReason.SingleFactorType(methodNames, factorTypesUnion))
        }
        return Reachability.NotReachable(
            if (methodNames.size >= 2) UnreachableReason.CombinationCapped(maxEnrolledUnderAcr)
            else UnreachableReason.SingleMethodCapped(methodNames.first(), maxEnrolledUnderAcr)
        )
    }

    override fun enrollmentCandidates(ctx: CandidateContext): List<ToolId> {
        val account = checkNotNull(ctx.account) { "enrollmentCandidates requires an account in CandidateContext" }
        val activeMethods = account.activeAuthenticationMethods.map { it.method }.toSet()
        return toolRegistry.tools()
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
        val usedMethods = evidence.methods.map { it.method.value }.toSet()

        // With a known account, only KNOWN_ACCOUNT_AUTH tools, never an ACCOUNT_LOOKUP_AUTH sibling that
        // resolves the account itself (docs/03-tool-architektur.md).
        //
        // Filtered to what is offerable here before computing singleMethodSuffices. A method that
        // cannot be offered on this channel (e.g. `auth-qr` in the App) must not count as "one
        // method alone suffices", or it would suppress the two-factor fallback for the others.
        val eligible = account.activeAuthenticationMethods
            .mapNotNull { m -> descriptorFor(m.method)?.let { m to it } }
            .filter { (_, d) -> ctx.availableTools == null || d.toolId in ctx.availableTools }
            // A key-bound method only on the device holding its credential, and only while that
            // device is linked to this account; anything else would fail for sure (docs/09-dpop.md).
            .filter { (m, d) -> d.usableByCaller(m.boundKeyRef, ctx.bindingKeyRef, ctx.linkedAccountId, account.accountId) }

        // Below loa3, MFA is needed when no single offerable method's capped level reaches
        // requiredAcr. Then every method adding a factor type not yet proven is worth offering.
        val singleMethodSuffices = eligible.any { (m, d) -> cappedAcr(m, d) >= requiredAcr }

        return eligible.filter { (m, _) -> m.method !in usedMethods }.mapNotNull { (m, d) ->
            // The evidence as it would be if this candidate were also proven.
            val projected = SessionEvidence(
                evidence.methods + MethodEvidence(
                    MethodName(m.method), cappedAcr(m, d), AcrLevel.parse(m.enrolledUnderAcr), d.factorTypes,
                    source = "simulation", amrSourceId = "simulation", provenAt = clock.instant()
                ),
            )
            val helpsLevel = applyMfaBump(baseAcr(projected.methods), projected) >= requiredAcr
            val helpsMfa = !singleMethodSuffices && (d.factorTypes - evidence.factorTypes).isNotEmpty()
            d.toolId.takeIf { helpsLevel || helpsMfa }
        }.distinct() // several instances of one method (devices) offer its AUTH tool once
    }

    override fun reIdentCandidates(ctx: CandidateContext): List<ToolId> {
        val requiredAcr = ctx.requiredAcr
        val evidence = countingToward(ctx.evidence, requiredAcr)
        val usedMethods = evidence.methods.map { it.method.value }.toSet()
        // An identification's amr need not be its method name (ident-nect reports
        // `nect-<procedure>`), so "already used" also checks which tool produced the evidence.
        val usedTools = evidence.methods.map { it.amrSourceId }.toSet()
        return toolRegistry.tools()
            // Role, not category: a CORRELATION step is no fresh proof of identity (ADR-18).
            .filter { it.role == ToolRole.IDENTIFICATION }
            .filter { it.method !in usedMethods && it.toolId.value !in usedTools }
            .filter { it.maxAcr >= requiredAcr }
            // Same precondition gate as every other candidate path.
            .filter { descriptor -> descriptor.requires.all { requiresSatisfied(it, ctx.account) } }
            .map { it.toolId }
    }

    /** Highest loa among [factors]' own per-method claims - see [MethodEvidence.loa]. */
    private fun baseAcr(factors: List<MethodEvidence>): AcrLevel = factors.maxOfOrNull { it.loa } ?: AcrLevel.NONE

    /** What proving [method] can give at most: the tool's level, capped by the enrollment's (ADR-5). */
    private fun cappedAcr(method: AuthMethodView, descriptor: Tool): AcrLevel =
        AcrLevel.min(AcrLevel.parse(method.enrolledUnderAcr), descriptor.maxAcr)

    /**
     * The MFA bump over one session's evidence, capped by the highest [MethodEvidence.enrolledUnderAcr]
     * among it. Does not filter by axis: the caller must pass authenticator evidence only, or an
     * identification could buy MFA credit it already priced into its own loa. A method without
     * `enrolledUnderAcr` adds nothing to the cap (docs/05-api.md Abschnitt 3b).
     */
    private fun applyMfaBump(base: AcrLevel, evidence: SessionEvidence): AcrLevel {
        val distinctMethods = evidence.methods.map { it.method }.distinct().size
        val maxEnrolledUnderAcr = evidence.methods.mapNotNull { it.enrolledUnderAcr }.maxOrNull() ?: AcrLevel.NONE
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
    private fun descriptorFor(method: String): Tool? = toolRegistry.toolOf(method, ToolRole.KNOWN_ACCOUNT_AUTH)

    private fun requiresMfa(requiredAcr: AcrLevel) = requiredAcr >= MFA_FROM_ACR

    companion object {
        /** Keycloak's `loa-max-age` for LoA 2 (keycloak-migrations V5). */
        val DEFAULT_LOA2_MAX_AGE: Duration = Duration.ofMinutes(30)

        /** How old the latest proof may be before self-service asks for a new one. */
        val DEFAULT_SELF_SERVICE_MAX_AGE: Duration = Duration.ofMinutes(5)

        /** The highest level an aged proof still carries. */
        private val AGELESS_CEILING = AcrLevel.LOA1

        private val MFA_FROM_ACR = AcrLevel.LOA3

        /** The highest level the combination bump may produce (see [combinedAcr]). */
        private val NIST_COMBINATION_CEILING = AcrLevel.LOA2
    }
}
