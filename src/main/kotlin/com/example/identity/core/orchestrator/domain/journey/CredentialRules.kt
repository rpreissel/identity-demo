package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.ToolCatalog
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.policy.Reachability
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.ToolModule
import com.example.identity.contract.tool_api.ToolRole

/*
 * At which level a credential counts, which device link follows from succeeding, and what else falls
 * when a credential or an attribute goes. Pure, like AccountRules.kt: `JourneyActionExecutor` reads,
 * asks here, and writes (docs/adr/ADR-040-fachkern-im-paket-domain.md).
 */

/**
 * The level a new credential or an attested anchor is written under: what the session had already
 * established before this completion, never more (self-escalation, ADR-5). With nothing behind the
 * session ("Enrollment zuerst" without identification) it is the flat baseline, not the tool's
 * `maxAcr`: an uncorroborated self-registered credential is self-asserted, however strong the tool.
 */
fun levelToWriteUnder(sessionAcr: AcrLevel): AcrLevel =
    if (sessionAcr == AcrLevel.NONE) AcrLevels.DEFAULT_REQUIRED_ACR else sessionAcr

/**
 * The level a proof counts at: what the tool achieved, capped by the level the used credential was
 * enrolled under (ADR-5). With several instances the used one is the one on the caller's key
 * ([ToolModule.onePerDevice]). If that still leaves more than one, the lowest cap applies: a level
 * is never granted on a guess.
 */
fun proofLevel(active: List<AuthMethodView>, module: ToolModule, bindingKeyRef: String?, achieved: AcrLevel?): AcrLevel {
    check(active.isNotEmpty()) { "No active instance to count the proof against" }
    val used = active.filter { !module.onePerDevice || module.livesOn(it.boundKeyRef, bindingKeyRef) }.ifEmpty { active }
    val cap = used.map { it.enrolledUnderAcr?.let(AcrLevel::parse) }.reduce { a, b -> AcrLevel.min(a, b) }
    return AcrLevel.min(achieved, cap)
}

/**
 * Whether succeeding at [intent] links this device to [accountId] on its own. Never as a rebind:
 * an ordinary flow is consent to be recognized next time, not to take the device away from another
 * account (which also revokes that account's device credentials). A rebind needs an explicit
 * answer (`ConfirmDeviceRebind`).
 */
fun linksDeviceImplicitly(intent: AuthIntent, linkedTo: AccountId?, accountId: AccountId): Boolean =
    intent.bindsDeviceImplicitly && (linkedTo == null || linkedTo == accountId)

/**
 * The active methods of [account] whose credential lives on [bindingKeyRef], i.e. those that stop
 * working when that key moves to another account.
 */
fun credentialsLivingOn(account: AccountProfile?, bindingKeyRef: String, catalog: ToolCatalog): List<AuthMethodView> =
    account?.activeAuthenticationMethods.orEmpty().filter { method ->
        catalog.moduleOf(method.method)?.livesOn(method.boundKeyRef, bindingKeyRef) == true
    }

/**
 * What else falls when a credential or an attribute goes, read off the existing `requires` gates.
 * Revoking a method retracts its claims; a method whose `requires` named one of them loses its
 * precondition and falls too, taking its own claims along. Hence a fixpoint. A claim that survives
 * (an account-owned anchor like EMAIL, or one another active method asserts) keeps its dependents.
 * [claimedTypes] looks up which attribute types a method instance asserted.
 */
class MethodDependencies(
    private val account: AccountProfile,
    private val catalog: ToolCatalog,
    private val claimedTypes: (AuthMethodView) -> Set<AttributeType>,
) {
    /** The active credentials that cannot outlive [target] - transitively. */
    fun dependentsOf(target: AuthMethodView): List<AuthMethodView> {
        val falling = listOf(target)
        val lost = claimedBy(falling) - claimedBy(standingBesides(falling))
        return dependentsOfLostClaims(lost, falling)
    }

    /**
     * The fixpoint, for a revoked credential or a withdrawn attribute (then [falling] is empty).
     * Returns only the new casualties, not [falling] itself.
     */
    fun dependentsOfLostClaims(lost: Set<AttributeType>, falling: List<AuthMethodView>): List<AuthMethodView> {
        val casualties = falling.toMutableList()
        var lostSoFar = lost
        while (true) {
            val next = standingBesides(casualties).filter { requiresAnyOf(it, lostSoFar) }
            if (next.isEmpty()) return casualties.drop(falling.size)
            casualties += next
            // A casualty takes its own claims with it - unless something still standing asserts
            // the same type, which is why this is recomputed rather than unioned.
            lostSoFar = lostSoFar + (claimedBy(casualties) - claimedBy(standingBesides(casualties)))
        }
    }

    /**
     * Revoking [target]: it goes with its dependents, unless the account could then no longer reach
     * the channel's [floor] (self-lockout guard). Dependents come first in [Removal.Allowed.falling],
     * so no dependent credential outlives what it depends on.
     */
    fun removal(target: AuthMethodView, policy: AuthPolicy, floor: AcrLevel): Removal {
        val dependents = dependentsOf(target)
        return guarded(dependents + target, dependents, policy, floor)
    }

    /**
     * Withdrawing [attributeType]: every credential that requires it falls, unless the account could
     * then no longer reach the channel's [floor]. The attribute itself is not a method, so
     * [Removal.Allowed.falling] holds only the dependents.
     */
    fun retraction(attributeType: AttributeType, policy: AuthPolicy, floor: AcrLevel): Removal {
        val dependents = dependentsOfLostClaims(lost = setOf(attributeType), falling = emptyList())
        return guarded(dependents, dependents, policy, floor)
    }

    private fun guarded(falling: List<AuthMethodView>, dependents: List<AuthMethodView>, policy: AuthPolicy, floor: AcrLevel): Removal =
        if (policy.reachability(without(falling), floor) is Reachability.Reachable) Removal.Allowed(falling)
        else Removal.BelowFloor(alsoFalling = dependents.map { it.method }.distinct())

    /** The account as it would be with [falling] deactivated - what the floor check runs against. */
    fun without(falling: Collection<AuthMethodView>): AccountProfile {
        val ids = falling.map { it.id }.toSet()
        return account.copy(authenticationMethods = account.authenticationMethods.map { if (it.id in ids) it.copy(active = false) else it })
    }

    private fun claimedBy(instances: List<AuthMethodView>): Set<AttributeType> = instances.flatMap(claimedTypes).toSet()

    /** The active credentials that stay when [falling] goes. */
    private fun standingBesides(falling: Collection<AuthMethodView>): List<AuthMethodView> {
        val fallingIds = falling.map { it.id }.toSet()
        return account.activeAuthenticationMethods.filter { it.id !in fallingIds }
    }

    /** Whether [instance]'s enrollment declares a precondition on one of [attributeTypes]. */
    private fun requiresAnyOf(instance: AuthMethodView, attributeTypes: Set<AttributeType>): Boolean =
        catalog.tools()
            .filter { it.method == instance.method && it.role == ToolRole.ENROLLMENT }
            .any { descriptor -> descriptor.requires.any { it.attributeType in attributeTypes } }
}

/** What [MethodDependencies.removal] and [MethodDependencies.retraction] decide. */
sealed interface Removal {
    /** May go: revoke [falling] in this order. */
    data class Allowed(val falling: List<AuthMethodView>) : Removal

    /** Refused: the channel's floor would be out of reach; [alsoFalling] names the dependents' methods. */
    data class BelowFloor(val alsoFalling: List<String>) : Removal
}
