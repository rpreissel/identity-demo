package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.core.orchestrator.domain.policy.CandidateContext
import com.example.identity.core.orchestrator.domain.policy.requiresSatisfied
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.Tool
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.core.orchestrator.domain.journey.state.JourneyState
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.journey.state.OfferingState

/**
 * Which tools from the catalog qualify for a given kind of offer, as questions to the
 * [JourneyContext]: the account in hand and the channel's floor are read from it, so a journey only
 * names what differs (a step-up's own target). Everything is derived from the registered
 * descriptors (docs/03-tool-architektur.md #1); no toolId is spelled out here, so a new method joins
 * an offer by declaring its role.
 *
 * Availability is applied here as well as live in [JourneyState.activatable]. This layer makes the
 * journey's decision correct (e.g. "fall through to identification because nothing else is left");
 * `activatable()` can only narrow an offer, not pick a different state.
 */

/**
 * [state] offering [tools], or null when there is nothing to offer, so the caller names its way
 * out: `offer(ctx.authCandidates(), ::AuthChoice) ?: requireRegister()`.
 */
internal fun <S : OfferingState> offer(tools: List<ToolId>, state: (Offer) -> S): Transition.To? =
    if (tools.isEmpty()) null else Transition.To(state(Offer(tools)))

/**
 * [ToolRole.IDENTIFICATION] only, never a [ToolRole.CORRELATION] step, which proves nothing on its
 * own (ADR-18). Hence matching on the role: `category == IDENT` matches both. A tool whose `requires`
 * the account does not meet is not offered.
 */
internal fun JourneyContext.identificationCandidates(): List<ToolId> = identCandidates(ToolRole.IDENTIFICATION)

/** The mirror image: the correlation steps [identificationCandidates] deliberately leaves out. */
internal fun JourneyContext.assignmentCandidates(): List<ToolId> = identCandidates(ToolRole.CORRELATION)

/** Every tool that resolves the account itself from a submitted identifier. */
internal fun JourneyContext.lookupLoginCandidates(): List<ToolId> = availableToolsOf(ToolRole.ACCOUNT_LOOKUP_AUTH)

/** The tools that approve another channel's request (role [ToolRole.PEER_APPROVAL]). */
internal fun JourneyContext.peerApprovalCandidates(): List<ToolId> = availableToolsOf(ToolRole.PEER_APPROVAL)

/** The attestation tools that prove control of [attributeType] (e.g. `confirm-email` for EMAIL). */
internal fun JourneyContext.attestationCandidates(attributeType: AttributeType): List<ToolId> =
    availableToolsOf(ToolRole.ATTESTATION) { descriptor -> descriptor.claims.any { it.attributeType == attributeType } }

/**
 * The auth tool for a credential of the account in hand that lives on this device
 * ([Tool.onePerDevice]), if any. It is the fastest offer and the only one that can succeed without
 * further input.
 */
internal fun AccountJourneyContext.deviceKeyCandidate(): ToolId? {
    val deviceAuthTools = catalog.tools().filter { it.role == ToolRole.KNOWN_ACCOUNT_AUTH && it.onePerDevice }
    val preferred = deviceAuthTools.firstOrNull { descriptor ->
        account.activeAuthenticationMethods.any {
            it.method == descriptor.method && descriptor.usableByCaller(it.boundKeyRef, bindingKeyRef, linkedAccountId, account.accountId)
        }
    }?.toolId
    return preferred?.takeIf { it in availableTools }
}

/** The account's methods that can still raise this session toward [targetAcr] (by default the channel's floor). */
internal fun AccountJourneyContext.authCandidates(targetAcr: AcrLevel = acrFloor): List<ToolId> =
    filterAvailable(policy.authCandidates(account, candidateContext(targetAcr)))

/**
 * Every active KNOWN_ACCOUNT_AUTH method of the account, for a fresh "are you still there?"
 * re-confirmation (e.g. before deleting the account). Unlike [authCandidates] it keeps methods
 * already proven this session, since re-presenting the same factor is a valid answer. No acr target:
 * any active factor counts.
 */
internal fun AccountJourneyContext.reconfirmationCandidates(): List<ToolId> {
    return availableToolsOf(ToolRole.KNOWN_ACCOUNT_AUTH) { descriptor ->
        val method = account.activeAuthenticationMethods.firstOrNull { it.method == descriptor.method }
        // A device-bound credential only works on the device it was enrolled on
        // (docs/03-tool-architektur.md); the descriptor decides.
        method != null && descriptor.usableByCaller(method.boundKeyRef, bindingKeyRef, linkedAccountId, account.accountId)
    }
}

/** New methods for the account in hand, or for a registration that has not created its account yet. */
internal fun JourneyContext.enrollmentCandidates(): List<ToolId> =
    filterAvailable(policy.enrollmentCandidates(account, candidateContext(acrFloor)))

/** The factor kinds the account's active methods cover, read from their enrollment descriptors. */
internal fun AccountJourneyContext.coveredFactorKinds(): Set<FactorType> {
    val activeMethods = account.activeAuthenticationMethods.map { it.method }.toSet()
    return catalog.tools()
        .filter { it.role == ToolRole.ENROLLMENT && it.method in activeMethods }
        .flatMap { it.factorTypes }
        .toSet()
}

/**
 * Enrollment tools whose method adds a factor kind the account's methods do not cover yet. Only a
 * method this channel can also prove counts: without an available auth tool, enrolling it would not
 * lift what the account can reach here (e.g. the email login while `auth-email` is switched off).
 */
internal fun AccountJourneyContext.missingFactorKindCandidates(): List<ToolId> {
    val covered = coveredFactorKinds()
    val provableMethods = catalog.tools()
        .filter { it.role == ToolRole.KNOWN_ACCOUNT_AUTH && it.toolId in availableTools }
        .map { it.method }
        .toSet()
    return enrollmentCandidates()
        .map { catalog.toolOf(it) }
        .filter { it.role == ToolRole.ENROLLMENT && it.method in provableMethods }
        .filter { (it.factorTypes - covered).isNotEmpty() }
        .map { it.toolId }
}

/** The identification tools that reach [targetAcr] on this channel. */
internal fun JourneyContext.reIdentificationCandidates(targetAcr: AcrLevel): List<ToolId> =
    filterAvailable(policy.reIdentCandidates(account, candidateContext(targetAcr)))

private fun JourneyContext.filterAvailable(ids: List<ToolId>): List<ToolId> = ids.filter { it in availableTools }

/** The available tools of one [role] in catalog order, optionally narrowed by [matches]. */
private inline fun JourneyContext.availableToolsOf(role: ToolRole, matches: (Tool) -> Boolean = { true }): List<ToolId> =
    filterAvailable(catalog.tools().filter { it.role == role && matches(it) }.map { it.toolId })

private fun JourneyContext.identCandidates(role: ToolRole): List<ToolId> =
    availableToolsOf(role) { descriptor -> descriptor.requires.all { requiresSatisfied(it, account) } }

private fun JourneyContext.candidateContext(targetAcr: AcrLevel): CandidateContext =
    CandidateContext(
        evidence = evidence,
        requiredAcr = targetAcr,
        bindingKeyRef = bindingKeyRef,
        linkedAccountId = linkedAccountId,
        availableTools = availableTools
    )
