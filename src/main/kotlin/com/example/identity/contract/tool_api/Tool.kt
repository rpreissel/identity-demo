package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.texts.Text
import kotlin.reflect.KClass

/**
 * A tool's public identifier ("auth-sms", "enroll-password", ...). Written out in its module's
 * declaration and checked there against its method and role ([ToolRole.toolIdFor]), so it reads
 * as it appears in URLs and docs but cannot be chosen freely. A value class keeps it apart from a
 * method name.
 */
@JvmInline
value class ToolId(val value: String) {
    override fun toString(): String = value
}

/**
 * One procedure ("kobil", "sms") and the tools it consists of: one per [ToolRole] it plays. What
 * all its tools share is stated here once, so a method has one ceiling, one key binding, one
 * reason to be a demonstration (docs/03-tool-architektur.md #1). Built only by [toolModule]; all
 * modules in the application context together are the tool catalog, there is no central list.
 */
class ToolModule internal constructor(
    /** The method's name, e.g. `"sms"`. What an enrolled credential and an `amr` entry carry. */
    val method: String,
    /** The factor kinds a proof of this method provides at most. */
    val factorTypes: Set<FactorType>,
    /** The highest level a proof of this method can achieve, e.g. [AcrLevel.LOA2]. */
    val maxAcr: AcrLevel,
    /**
     * Each credential lives on one caller key (a non-extractable device key) and several may
     * coexist on one account, one per device. It is usable only from that caller and worthless
     * once the key is bound to another account (docs/09-dpop.md). By default a new enrollment
     * replaces the active one.
     */
    val onePerDevice: Boolean,
    /**
     * Set when the method vouches for something it cannot prove (`device` believes the user
     * verification a device proof merely claims). Outside `demo.mode` its tools are neither
     * offered nor activatable, and no setting switches them on (ADR-36). A real counterpart is a
     * method of its own.
     */
    val demoOnly: DemoOnly?,
    /** The [StepData] shapes its tools answer with, for the API description. */
    val stepData: List<KClass<out StepData>>,
    specs: List<RoleSpec>,
) {
    init {
        specs.forEach { spec ->
            val expected = spec.role.toolIdFor(method)
            require(spec.toolId == expected.value) {
                "Tool '${spec.toolId}' in module '$method' must be called '$expected' (${spec.role})"
            }
        }
        // Identification and correlation share the prefix, so this also forbids having both.
        val duplicates = specs.groupBy { it.toolId }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) { "Module '$method' declares $duplicates more than once" }
    }

    /** Its tools, one per role it plays. */
    val tools: List<Tool> = specs.map { Tool(this, it) }

    /** Every attribute its tools assert or require. */
    val attributes: Set<AttributeType> =
        tools.flatMap { tool -> tool.claims.map { it.attributeType } + tool.requires.map { it.attributeType } }.toSet()

    init {
        // Identity matching has no path for a KVNR a tool merely read: only the Personenverzeichnis vouches for one.
        val unvouchedKvnr = tools.filter { tool ->
            tool.claims.any { it.attributeType == AttributeType.KVNR && it.source != ClaimSource.PERSON_DIRECTORY }
        }
        check(unvouchedKvnr.isEmpty()) {
            "Only the Personenverzeichnis may vouch for a KVNR, but ${unvouchedKvnr.map { it.toolId }} declare one from elsewhere"
        }
    }

    /**
     * The [ClaimSource] of a claim the [role] tool asserts on its own authority: the tool's id. What
     * its [Tool.claims] declares for such a claim.
     */
    fun source(role: ToolRole): ClaimSource = ClaimSource(role.toolIdFor(method).value)

    /** Whether a credential instance bound to [boundKeyRef] lives on the caller's [callerBindingKeyRef]. */
    fun livesOn(boundKeyRef: String?, callerBindingKeyRef: String?): Boolean =
        onePerDevice && boundKeyRef == callerBindingKeyRef

    override fun toString(): String = "ToolModule($method)"
}

/**
 * One tool: what its module says, plus what this role says. Built only by its [ToolModule];
 * everything the module states it passes through unchanged.
 */
class Tool internal constructor(
    /** The procedure this tool belongs to, shared with its sibling tools. */
    val module: ToolModule,
    spec: RoleSpec,
) {
    /** The role this tool plays for [method]: fixed by the factory that declared it. See [ToolRole]. */
    val role: ToolRole = spec.role

    /** The tool's public, stable identifier, as declared and checked against [method] and [role]. */
    val toolId: ToolId = ToolId(spec.toolId)

    /** The method's name, e.g. `"sms"`. */
    val method: String get() = module.method

    /**
     * The step a freshly activated tool session starts on. Must match the `nextStep` of the tool's
     * first `ToolOutcome.InProgress`.
     */
    val startStep: String = spec.startStep ?: role.defaultStartStep

    /**
     * The attributes this tool may assert about its subject, each with its [ClaimSource]. This is
     * the declaration, answerable before any run (offering, planning). The [Claim]s of one concrete
     * run are checked against it by [assertClaimsCovered].
     */
    val claims: Set<ClaimDeclaration> =
        spec.claims.map { ClaimDeclaration(it, spec.vouchedBy ?: ClaimSource(toolId.value)) }.toSet()

    /**
     * What the account must already have for this tool to be offered: each [AttributeType] at no
     * less than its [ClaimTrust], checked against the consolidated value including retractions
     * (ADR-12). It is a standing precondition: when a requirement stops being established, the
     * credential is revoked as well. That is how one method depends on another (ADR-24).
     */
    val requires: Set<ClaimRequirement> = spec.requires

    /**
     * Non-`null` if this tool needs no step of the user: activating it completes it at once (e.g.
     * `enroll-email`, whose address was proven before). The text tells the user beforehand what
     * choosing it does. Such a tool is never started automatically, even as the only candidate,
     * because it would change the account without the user having chosen anything.
     */
    val withoutUserStep: Text? = spec.withoutUserStep

    /**
     * Whether this tool proves nothing itself: an attestation, a peer approval, an opt-in. It then
     * provides no factor and reports no `amr`.
     */
    private val provesNothing: Boolean = spec.provesNothing

    /** The factor kinds this tool provides at most: its module's, none if it proves nothing. */
    val factorTypes: Set<FactorType> = if (provesNothing) emptySet() else module.factorTypes

    /** The highest level this tool can achieve: always its module's. */
    val maxAcr: AcrLevel get() = module.maxAcr

    /** Several active instances may coexist on one account ([ToolModule.onePerDevice]). */
    val allowsMultipleInstances: Boolean get() = module.onePerDevice

    /** The credential lives on one caller key ([ToolModule.onePerDevice]). */
    val boundToCallerKey: Boolean get() = module.onePerDevice

    /** See [ToolModule.demoOnly]. */
    val demoOnly: DemoOnly? get() = module.demoOnly

    /** The `amr` of [outcome]: what it reported, else this tool's method (none if it proves nothing). */
    fun amrOf(outcome: ToolOutcome.Completed): List<String> =
        outcome.amr ?: if (provesNothing) emptyList() else listOf(method)

    /** The level [outcome] achieved: what it reported, else this tool's [maxAcr]. */
    fun levelOf(outcome: ToolOutcome.Completed): AcrLevel = outcome.achievedAcr ?: maxAcr

    /** The factor kinds [outcome] proved: what it reported, else this tool's [factorTypes]. */
    fun factorsOf(outcome: ToolOutcome.Completed): Set<FactorType> = outcome.factorTypes ?: factorTypes

    /**
     * Whether [outcome] stays within this declaration: no level above [maxAcr], no factor kind
     * outside [factorTypes]. The declaration is what the policy planned with; a run must not prove
     * more than that.
     */
    fun staysWithin(outcome: ToolOutcome.Completed): Boolean =
        levelOf(outcome) <= maxAcr && factorTypes.containsAll(factorsOf(outcome))

    /**
     * Is a credential instance bound to [boundKeyRef] usable by this caller right now? A key-bound
     * one must live on the caller's key, and the device must still be linked to the credential's
     * account: a device is bound to one account at a time (docs/09-dpop.md).
     */
    fun usableByCaller(boundKeyRef: String?, callerBindingKeyRef: String?, linkedAccountId: AccountId?, accountId: AccountId): Boolean =
        !boundToCallerKey || (module.livesOn(boundKeyRef, callerBindingKeyRef) && linkedAccountId == accountId)

    override fun toString(): String = toolId.value
}

/** What a module proves at most: the factor kinds and the level they reach together. */
data class Proves(val factorTypes: Set<FactorType>, val upTo: AcrLevel)

/** [Proves] for [toolModule]: e.g. `factors(POSSESSION, KNOWLEDGE, upTo = AcrLevel.LOA2)`. */
fun factors(vararg factorTypes: FactorType, upTo: AcrLevel): Proves = Proves(factorTypes.toSet(), upTo)

/**
 * Declares one procedure and its [tools], one per role. Each tool comes from the factory of its
 * role ([identify], [enroll], [login], ...), which takes only what that role may say, and names its
 * id; the module checks the id against its method and role and that no role appears twice.
 */
fun toolModule(
    method: String,
    proves: Proves,
    demoOnly: String? = null,
    onePerDevice: Boolean = false,
    stepData: List<KClass<out StepData>> = emptyList(),
    tools: List<RoleSpec>,
): ToolModule = ToolModule(
    method = method,
    factorTypes = proves.factorTypes,
    maxAcr = proves.upTo,
    onePerDevice = onePerDevice,
    demoOnly = demoOnly?.let(::DemoOnly),
    stepData = stepData,
    specs = tools,
)

/** One tool as its role's factory declares it. Each role has its own type, so a factory takes only its own. */
sealed interface RoleSpec {
    val toolId: String
    val role: ToolRole
    val startStep: String?
    val claims: Set<AttributeType> get() = emptySet()
    val vouchedBy: ClaimSource? get() = null
    val requires: Set<ClaimRequirement> get() = emptySet()
    val withoutUserStep: Text? get() = null
    val provesNothing: Boolean get() = false
}

/** An identification or a correlation. */
class IdentSpec internal constructor(
    override val toolId: String,
    override val role: ToolRole,
    override val claims: Set<AttributeType>,
    override val vouchedBy: ClaimSource?,
    override val requires: Set<ClaimRequirement>,
    override val startStep: String?,
) : RoleSpec

class AttestationSpec internal constructor(
    override val toolId: String,
    override val claims: Set<AttributeType>,
    override val startStep: String?,
) : RoleSpec {
    override val role get() = ToolRole.ATTESTATION
    override val provesNothing get() = true
}

class EnrollmentSpec internal constructor(
    override val toolId: String,
    override val claims: Set<AttributeType>,
    override val requires: Set<ClaimRequirement>,
    override val startStep: String?,
    override val withoutUserStep: Text?,
    override val provesNothing: Boolean,
) : RoleSpec {
    override val role get() = ToolRole.ENROLLMENT
}

class AuthSpec internal constructor(
    override val toolId: String,
    override val role: ToolRole,
    override val startStep: String?,
) : RoleSpec

class ApprovalSpec internal constructor(override val toolId: String, override val startStep: String?) : RoleSpec {
    override val role get() = ToolRole.PEER_APPROVAL
    override val provesNothing get() = true
}

/**
 * The attributes every identification asserts, so it can be found again by name, first name and
 * date of birth, even after the account is deleted (the change log's search key, ADR-39).
 */
val IDENTIFICATION_FINDABLE_BY: Set<AttributeType> =
    setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)

/**
 * `ident-<method>`: resolves identity (e.g. `ident-fsc`). Asserts [IDENTIFICATION_FINDABLE_BY] and
 * [also], each vouched for by [vouchedBy] (the tool itself if `null`).
 */
fun identify(
    toolId: String,
    also: Set<AttributeType> = emptySet(),
    vouchedBy: ClaimSource? = null,
    requires: Set<ClaimRequirement> = emptySet(),
    startStep: String? = null,
): IdentSpec = IdentSpec(toolId, ToolRole.IDENTIFICATION, IDENTIFICATION_FINDABLE_BY + also, vouchedBy, requires, startStep)

/** `ident-<method>`: attaches an already attested identity to its register person (e.g. `ident-kvnr`, ADR-18). */
fun correlate(
    toolId: String,
    claims: Set<AttributeType>,
    vouchedBy: ClaimSource? = null,
    requires: Set<ClaimRequirement> = emptySet(),
    startStep: String? = null,
): IdentSpec = IdentSpec(toolId, ToolRole.CORRELATION, claims, vouchedBy, requires, startStep)

/** `confirm-<method>`: attests [claims] the account owns, on this tool's own authority (e.g. `confirm-email`). */
fun confirm(toolId: String, claims: Set<AttributeType>, startStep: String? = null): AttestationSpec =
    AttestationSpec(toolId, claims, startStep)

/**
 * `enroll-<method>`: creates a credential. [claims] are asserted on this tool's own authority.
 * [withoutUserStep]: see [Tool.withoutUserStep]. [optInOnly]: the credential is a consent without
 * secret (e.g. `enroll-qr`) and proves nothing itself.
 */
fun enroll(
    toolId: String,
    claims: Set<AttributeType> = emptySet(),
    requires: Set<ClaimRequirement> = emptySet(),
    startStep: String? = null,
    withoutUserStep: Text? = null,
    optInOnly: Boolean = false,
): EnrollmentSpec = EnrollmentSpec(toolId, claims, requires, startStep, withoutUserStep, optInOnly)

/** `auth-<method>`: proves a credential of the account the channel already knows. */
fun login(toolId: String, startStep: String? = null): AuthSpec = AuthSpec(toolId, ToolRole.KNOWN_ACCOUNT_AUTH, startStep)

/** `auth-<method>-lookup`: proves a credential and finds the account (or invitation) from the input itself. */
fun lookupLogin(toolId: String, startStep: String? = null): AuthSpec = AuthSpec(toolId, ToolRole.ACCOUNT_LOOKUP_AUTH, startStep)

/** `approve-<method>`: approves or declines a pending request from another channel (e.g. `approve-qr`). */
fun approve(toolId: String, startStep: String? = null): ApprovalSpec = ApprovalSpec(toolId, startStep)

/** Why a method is only fit for a demonstration ([ToolModule.demoOnly]) - stated, so it can be reviewed. */
data class DemoOnly(val reason: String)

/**
 * The role a tool plays for its method. `(method, role)` identifies a concrete procedure and
 * yields its [ToolId]. Decisions ask for the role itself, never a coarser grouping: identification
 * and correlation look alike but only identification proves anything.
 */
enum class ToolRole(val defaultStartStep: String, private val idPattern: String) {
    /** Resolves identity (e.g. `ident-fsc`). Never establishes a durable credential. */
    IDENTIFICATION("input", "ident-%s"),

    /**
     * Attaches an already attested identity to its register person (e.g. `ident-kvnr` with the
     * Versichertennummer, ADR-18). It proves nothing by itself: the user supplies a mere
     * identifier. So it is never offered as a way to identify, records no evidence, and the act
     * is only accepted if the person matches what the account had attested. [Tool.maxAcr]
     * describes the attestation the step rests on, not what typing the number proved. The second
     * half of an identification, hence the same prefix.
     */
    CORRELATION("input", "ident-%s"),

    /** Creates a new credential for the method (e.g. `enroll-sms`). */
    ENROLLMENT("enroll", "enroll-%s"),

    /** Proves a credential for an account the channel already knows (e.g. `auth-sms`). */
    KNOWN_ACCOUNT_AUTH("auth", "auth-%s"),

    /**
     * Proves the same credential as its [KNOWN_ACCOUNT_AUTH] sibling, but resolves the account from a
     * submitted identifier (e.g. `auth-sms-lookup`).
     */
    ACCOUNT_LOOKUP_AUTH("auth", "auth-%s-lookup"),

    /**
     * Approves or declines a pending request from another channel (e.g. `approve-qr` deciding an
     * `auth-qr` pairing, docs/03-tool-architektur.md). All other roles act on their own channel.
     * Contributes nothing to its own channel's ACR/AMR and never closes a gap.
     */
    PEER_APPROVAL("input", "approve-%s"),

    /**
     * Attests an attribute the account owns and others depend on (e.g. `confirm-email`): asserts
     * claims, resolves no identity, leaves no credential, adds nothing to ACR/AMR
     * (docs/03-tool-architektur.md #2).
     */
    ATTESTATION("input", "confirm-%s");

    /** The id of the tool playing this role for [method]. */
    fun toolIdFor(method: String): ToolId = ToolId(idPattern.format(method))
}

/** A kind of authentication factor a method can provide. */
enum class FactorType {
    /** Something the user knows, e.g. a password. */
    KNOWLEDGE,
    /** Something the user has, e.g. a phone or a device key. */
    POSSESSION,
    /** Something the user is, e.g. biometrics. */
    INHERENCE
}
