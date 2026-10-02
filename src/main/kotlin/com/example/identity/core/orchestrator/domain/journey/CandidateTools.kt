package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.policy.CandidateContext
import com.example.identity.core.orchestrator.domain.policy.requiresSatisfied
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolId

/**
 * Which tools from the catalog qualify for a given kind of offer. Everything is derived from the
 * registered descriptors (docs/03-tool-architektur.md #1); no toolId is spelled out here, so a new
 * method joins an offer by declaring its role. The answer travels in a [JourneyState], which holds
 * the offer.
 */
internal object CandidateTools {

    /**
     * Availability is applied here as well as live in [JourneyState.activatable]. This layer makes
     * the strategy's decision correct (e.g. "fall through to identification because nothing else is
     * left"); `activatable()` can only narrow an offer, not pick a different state.
     */
    private fun JourneyContext.filterAvailable(ids: List<ToolId>): List<ToolId> = ids.filter { it in availableTools }

    /** The available tools of one [role] in catalog order, optionally narrowed by [matches]. */
    private inline fun JourneyContext.availableToolsOf(role: ToolRole, matches: (Tool) -> Boolean = { true }): List<ToolId> =
        filterAvailable(catalog.tools().filter { it.role == role && matches(it) }.map { it.toolId })

    private fun JourneyContext.candidateContext(targetAcr: AcrLevel, account: AccountProfile? = null): CandidateContext =
        CandidateContext(
            evidence = evidence,
            requiredAcr = targetAcr,
            account = account,
            bindingKeyRef = bindingKeyRef,
            linkedAccountId = linkedAccountId,
            availableTools = availableTools
        )

    /**
     * [ToolRole.IDENTIFICATION] only, never a [ToolRole.CORRELATION] step, which proves nothing
     * on its own (ADR-18). Hence matching on the role: `category == IDENT` matches both. A tool whose
     * `requires` the account does not meet is not offered.
     */
    fun forIdentification(ctx: JourneyContext): List<ToolId> = identCandidates(ctx, ToolRole.IDENTIFICATION)

    /** The mirror image: the correlation steps [forIdentification] deliberately leaves out. */
    fun forAssignment(ctx: JourneyContext): List<ToolId> = identCandidates(ctx, ToolRole.CORRELATION)

    private fun identCandidates(ctx: JourneyContext, role: ToolRole): List<ToolId> =
        ctx.availableToolsOf(role) { descriptor -> descriptor.requires.all { requiresSatisfied(it, ctx.account) } }

    /** Every tool that resolves the account itself from a submitted identifier. */
    fun forLookupLogin(ctx: JourneyContext): List<ToolId> = ctx.availableToolsOf(ToolRole.ACCOUNT_LOOKUP_AUTH)

    /** The tools that approve another channel's request (role [ToolRole.PEER_APPROVAL]). */
    fun forPeerApproval(ctx: JourneyContext): List<ToolId> = ctx.availableToolsOf(ToolRole.PEER_APPROVAL)

    /** The attestation tools that prove control of [attributeType] (e.g. `confirm-email` for EMAIL). */
    fun forAttestation(attributeType: AttributeType, ctx: JourneyContext): List<ToolId> =
        ctx.availableToolsOf(ToolRole.ATTESTATION) { descriptor -> descriptor.claims.any { it.attributeType == attributeType } }

    /**
     * The auth tool for a credential that lives on this device, if the account has one
     * ([Tool.boundToCallerKey]). It is the fastest offer and the only one that can succeed
     * without further input.
     */
    fun preferredDeviceAuth(account: AccountProfile, ctx: JourneyContext): ToolId? {
        val deviceAuthTools = ctx.catalog.tools()
            .filter { it.role == ToolRole.KNOWN_ACCOUNT_AUTH && it.boundToCallerKey }
        val preferred = deviceAuthTools.firstOrNull { descriptor ->
            account.activeAuthenticationMethods.any {
                it.method == descriptor.method && descriptor.usableByCaller(it.boundKeyRef, ctx.bindingKeyRef, ctx.linkedAccountId, account.accountId)
            }
        }?.toolId
        return preferred?.takeIf { it in ctx.availableTools }
    }

    fun forAuth(account: AccountProfile, targetAcr: AcrLevel, ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.policy.authCandidates(ctx.candidateContext(targetAcr, account)))

    /**
     * Every active KNOWN_ACCOUNT_AUTH method, for a fresh "are you still there?" re-confirmation (e.g.
     * before deleting the account). Unlike [forAuth] it keeps methods already proven this session,
     * since re-presenting the same factor is a valid answer. No acr target: any active factor counts.
     */
    fun forReconfirmation(account: AccountProfile, ctx: JourneyContext): List<ToolId> =
        ctx.availableToolsOf(ToolRole.KNOWN_ACCOUNT_AUTH) { descriptor ->
            val method = account.activeAuthenticationMethods.firstOrNull { it.method == descriptor.method }
            // A device-bound credential only works on the device it was enrolled on
            // (docs/03-tool-architektur.md); the descriptor decides.
            method != null && descriptor.usableByCaller(method.boundKeyRef, ctx.bindingKeyRef, ctx.linkedAccountId, account.accountId)
        }

    fun forEnrollment(account: AccountProfile, targetAcr: AcrLevel, ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.policy.enrollmentCandidates(ctx.candidateContext(targetAcr, account)))

    /** The factor kinds the account's active methods cover, read from their enrollment descriptors. */
    fun factorKindsOf(account: AccountProfile, ctx: JourneyContext): Set<FactorType> {
        val activeMethods = account.activeAuthenticationMethods.map { it.method }.toSet()
        return ctx.catalog.tools()
            .filter { it.role == ToolRole.ENROLLMENT && it.method in activeMethods }
            .flatMap { it.factorTypes }
            .toSet()
    }

    /**
     * Enrollment tools whose method adds a factor kind missing from [covered]. Only a method this
     * channel can also prove counts: without an available auth tool, enrolling it would not lift
     * what the account can reach here (e.g. the email login while `auth-email` is switched off).
     */
    fun forMissingFactorKind(account: AccountProfile, covered: Set<FactorType>, ctx: JourneyContext): List<ToolId> {
        val provableMethods = ctx.catalog.tools()
            .filter { it.role == ToolRole.KNOWN_ACCOUNT_AUTH && it.toolId in ctx.availableTools }
            .map { it.method }
            .toSet()
        return forEnrollment(account, ctx.acrFloor, ctx)
            .map { ctx.catalog.toolOf(it) }
            .filter { it.role == ToolRole.ENROLLMENT && it.method in provableMethods }
            .filter { (it.factorTypes - covered).isNotEmpty() }
            .map { it.toolId }
    }

    fun forReIdentification(targetAcr: AcrLevel, ctx: JourneyContext): List<ToolId> =
        ctx.filterAvailable(ctx.policy.reIdentCandidates(ctx.candidateContext(targetAcr)))
}
