package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.domain.AcrLevels
import com.example.identity.core.orchestrator.domain.AuthIntent
import com.example.identity.core.orchestrator.domain.ToolCatalog
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.CallerKeyBinding
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolCategory

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
 * ([keyBinding]). If that still leaves more than one, the lowest cap applies: a level is never
 * granted on a guess.
 */
fun proofLevel(active: List<AuthMethodView>, keyBinding: CallerKeyBinding?, bindingKeyRef: String?, achieved: AcrLevel?): AcrLevel {
    check(active.isNotEmpty()) { "No active instance to count the proof against" }
    val used = active.filter { keyBinding?.livesOn(it.details, bindingKeyRef) ?: true }.ifEmpty { active }
    val cap = used.map { it.enrolledUnderAcr?.let(AcrLevel::of) }.reduce { a, b -> AcrLevel.min(a, b) }
    return AcrLevel.min(achieved, cap)
}

/**
 * Whether succeeding at [intent] links this device to [accountId] on its own. Never as a rebind:
 * an ordinary flow is consent to be recognized next time, not to take the device away from another
 * account (which also revokes that account's device credentials). A rebind needs an explicit
 * answer (`ConfirmDeviceRebind`).
 */
fun linksDeviceImplicitly(intent: AuthIntent, linkedTo: Long?, accountId: Long): Boolean =
    intent.bindsDeviceImplicitly && (linkedTo == null || linkedTo == accountId)

/**
 * The active methods of [account] whose credential lives on [bindingKeyRef], i.e. those that stop
 * working when that key moves to another account. Resolved by `(method, KNOWN_ACCOUNT_AUTH)`: by method
 * name alone the enrollment tool would answer too, from the wrong declaration.
 */
fun credentialsLivingOn(account: AccountProfile?, bindingKeyRef: String, catalog: ToolCatalog): List<AuthMethodView> =
    account?.activeAuthenticationMethods.orEmpty().filter { method ->
        val binding = catalog.descriptors()
            .firstOrNull { it.role == ToolRole.KNOWN_ACCOUNT_AUTH && it.method == method.method }
            ?.keyBinding
        binding != null && binding.livesOn(method.details, bindingKeyRef)
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
        val lost = claimedBy(listOf(target)) - claimedBy(account.activeAuthenticationMethods.filter { it.id != target.id })
        return dependentsOfLostClaims(lost, falling = listOf(target))
    }

    /**
     * The fixpoint, for a revoked credential or a withdrawn attribute (then [falling] is empty).
     * Returns only the new casualties, not [falling] itself.
     */
    fun dependentsOfLostClaims(lost: Set<AttributeType>, falling: List<AuthMethodView>): List<AuthMethodView> {
        val casualties = falling.toMutableList()
        var lostSoFar = lost
        while (true) {
            val fallingIds = casualties.map { it.id }.toSet()
            val standing = account.activeAuthenticationMethods.filter { it.id !in fallingIds }
            val next = standing.filter { instance ->
                catalog.descriptors()
                    .filter { it.method == instance.method && it.role.category == ToolCategory.ENROLL }
                    .any { descriptor -> descriptor.requires.any { it.attributeType in lostSoFar } }
            }
            if (next.isEmpty()) return casualties.drop(falling.size)
            casualties += next
            // A casualty takes its own claims with it - unless something still standing asserts
            // the same type, which is why this is recomputed rather than unioned.
            val stillStanding = account.activeAuthenticationMethods.filter { it.id !in casualties.map { c -> c.id }.toSet() }
            lostSoFar = lostSoFar + (claimedBy(casualties) - claimedBy(stillStanding))
        }
    }

    /** The account as it would be with [falling] deactivated - what the floor check runs against. */
    fun without(falling: Collection<AuthMethodView>): AccountProfile {
        val ids = falling.map { it.id }.toSet()
        return account.copy(authenticationMethods = account.authenticationMethods.map { if (it.id in ids) it.copy(active = false) else it })
    }

    private fun claimedBy(instances: List<AuthMethodView>): Set<AttributeType> = instances.flatMap(claimedTypes).toSet()
}
