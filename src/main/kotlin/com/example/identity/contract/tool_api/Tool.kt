package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.texts.Text

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
 * reason to be a demonstration (docs/03-tool-architektur.md #2). Built only by [toolModule]; its
 * tools are registered on it afterwards, one value each ([identify], [enroll], [login], ...), in
 * the module's own file. All modules in the application context together are the tool catalog,
 * there is no central list.
 */
class ToolModule internal constructor(
    /** The method's name, e.g. `"sms"`. What an enrolled credential and an `amr` entry carry. */
    val method: String,
    /**
     * What users call the method, e.g. „SMS“ - the one place it is named: the catalog sends it to
     * the app and the login pages (`GET /tools/catalog`), a credential of the method is listed by it.
     */
    val name: Text,
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
    /** The [StepData] shapes its tools answer with, by their `kind`. */
    val stepData: Map<String, StepDataShape>,
) {
    private val registered = mutableListOf<Tool>()
    private var frozen = false

    /**
     * Its tools, one per role it plays. The first read freezes the module: the catalog collects it
     * once, and a tool registered later would silently be missing from it.
     */
    val tools: List<Tool>
        get() = synchronized(registered) {
            frozen = true
            registered.toList()
        }

    /** Every attribute its tools assert or require. */
    val attributes: Set<AttributeType>
        get() = tools.flatMap { tool -> tool.claims.map { it.attributeType } + tool.requires.map { it.attributeType } }.toSet()

    /**
     * `ident-<method>`: resolves identity (e.g. `ident-fsc`). Asserts [IDENTIFICATION_FINDABLE_BY]
     * and [also], each vouched for by [vouchedBy] (the tool itself if `null`).
     */
    fun identify(
        toolId: String,
        versions: Set<Int>,
        hint: Text,
        also: Set<AttributeType> = emptySet(),
        vouchedBy: ClaimSource? = null,
        requires: Set<ClaimRequirement> = emptySet(),
        startStep: String? = null,
        name: Text? = null,
    ): Tool = register(toolId, ToolRole.IDENTIFICATION, hint, name, startStep, versions, IDENTIFICATION_FINDABLE_BY + also, vouchedBy, requires)

    /** `ident-<method>`: attaches an already attested identity to its register person (e.g. `ident-kvnr`, ADR-18). */
    fun correlate(
        toolId: String,
        versions: Set<Int>,
        hint: Text,
        claims: Set<AttributeType>,
        vouchedBy: ClaimSource? = null,
        requires: Set<ClaimRequirement> = emptySet(),
        startStep: String? = null,
        name: Text? = null,
    ): Tool = register(toolId, ToolRole.CORRELATION, hint, name, startStep, versions, claims, vouchedBy, requires)

    /** `confirm-<method>`: attests [claims] the account owns, on this tool's own authority (e.g. `confirm-email`). */
    fun confirm(toolId: String, versions: Set<Int>, hint: Text, claims: Set<AttributeType>, startStep: String? = null, name: Text? = null): Tool =
        register(toolId, ToolRole.ATTESTATION, hint, name, startStep, versions, claims, provesNothing = true)

    /**
     * `enroll-<method>`: creates a credential. [claims] are asserted on this tool's own authority.
     * [withoutUserStep]: see [Tool.withoutUserStep]. [optInOnly]: the credential is a consent
     * without secret (e.g. `enroll-qr`) and proves nothing itself. [changeable]: see [Tool.changeable].
     */
    fun enroll(
        toolId: String,
        versions: Set<Int>,
        hint: Text,
        claims: Set<AttributeType> = emptySet(),
        requires: Set<ClaimRequirement> = emptySet(),
        startStep: String? = null,
        withoutUserStep: Text? = null,
        optInOnly: Boolean = false,
        changeable: Boolean = false,
        name: Text? = null,
    ): Tool = register(
        toolId, ToolRole.ENROLLMENT, hint, name, startStep, versions, claims, requires = requires,
        withoutUserStep = withoutUserStep, provesNothing = optInOnly, changeable = changeable,
    )

    /** `auth-<method>`: proves a credential of the account the channel already knows. */
    fun login(toolId: String, versions: Set<Int>, hint: Text, startStep: String? = null, name: Text? = null): Tool =
        register(toolId, ToolRole.KNOWN_ACCOUNT_AUTH, hint, name, startStep, versions)

    /** `auth-<method>-lookup`: proves a credential and finds the account (or invitation) from the input itself. */
    fun lookupLogin(toolId: String, versions: Set<Int>, hint: Text, startStep: String? = null, name: Text? = null): Tool =
        register(toolId, ToolRole.ACCOUNT_LOOKUP_AUTH, hint, name, startStep, versions)

    /** `approve-<method>`: approves or declines a pending request from another channel (e.g. `approve-qr`). */
    fun approve(toolId: String, versions: Set<Int>, hint: Text, startStep: String? = null, name: Text? = null): Tool =
        register(toolId, ToolRole.PEER_APPROVAL, hint, name, startStep, versions, provesNothing = true)

    /**
     * Adds one tool. Its id must be the one its method and role give, and no id may come twice
     * (identification and correlation share the prefix, so this also forbids having both). Every
     * tool says in a few words what it does ([hint]); a [name] of its own is the exception, for a
     * tool that is named for what it does rather than for its method („Mit App anmelden“).
     * [versions] are the versions of its contract the server serves (ADR-51), each with a
     * controller of its own.
     */
    private fun register(
        toolId: String,
        role: ToolRole,
        hint: Text,
        name: Text?,
        startStep: String?,
        versions: Set<Int>,
        claims: Set<AttributeType> = emptySet(),
        vouchedBy: ClaimSource? = null,
        requires: Set<ClaimRequirement> = emptySet(),
        withoutUserStep: Text? = null,
        provesNothing: Boolean = false,
        changeable: Boolean = false,
    ): Tool = synchronized(registered) {
        check(!frozen) { "Tool '$toolId' registered after the catalog collected '$method': declare the tools of a module in its module file" }
        val expected = role.toolIdFor(method)
        require(toolId == expected.value) { "Tool '$toolId' in module '$method' must be called '$expected' ($role)" }
        require(registered.none { it.toolId == expected }) { "Module '$method' declares '$toolId' more than once" }
        require(!changeable || !onePerDevice) { "'$toolId' cannot be changeable: a method with one credential per device adds an instance instead" }
        require(versions.isNotEmpty() && versions.all { it >= 1 }) { "'$toolId' must serve at least one version, counted from 1" }
        val tool = Tool(this, expected, role, name ?: this.name, hint, startStep, versions.toSortedSet(), claims, vouchedBy, requires, withoutUserStep, provesNothing, changeable)
        // Identity matching has no path for a KVNR a tool merely read: only the Personenverzeichnis vouches for one.
        require(tool.claims.none { it.attributeType == AttributeType.KVNR && it.source != ClaimSource.PERSON_DIRECTORY }) {
            "Only the Personenverzeichnis may vouch for a KVNR, but '$toolId' declares one from elsewhere"
        }
        registered += tool
        tool
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
    /** The tool's public, stable identifier, as declared and checked against [method] and [role]. */
    val toolId: ToolId,
    /** The role this tool plays for [method]: fixed by the factory that declared it. See [ToolRole]. */
    val role: ToolRole,
    /** What users call it: its module's [ToolModule.name] unless the tool names itself. */
    val name: Text,
    /** What it does, in a few words, under its name in a selection („Code an die hinterlegte Telefonnummer“). */
    val hint: Text,
    startStep: String?,
    /** The versions of its contract the server serves, ascending (ADR-51). A client speaks one of them. */
    val versions: Set<Int>,
    claims: Set<AttributeType>,
    vouchedBy: ClaimSource?,
    /**
     * What the account must already have for this tool to be offered: each [AttributeType] at no
     * less than its [ClaimTrust], checked against the consolidated value including retractions
     * (ADR-12). It is a standing precondition: when a requirement stops being established, the
     * credential is revoked as well. That is how one method depends on another (ADR-24).
     */
    val requires: Set<ClaimRequirement>,
    /**
     * Non-`null` if this tool needs no step of the user: activating it completes it at once (e.g.
     * `enroll-email`, whose address was proven before). The text tells the user beforehand what
     * choosing it does. Such a tool is never started automatically, even as the only candidate,
     * because it would change the account without the user having chosen anything.
     */
    val withoutUserStep: Text?,
    /**
     * Whether this tool proves nothing itself: an attestation, a peer approval, an opt-in. It then
     * provides no factor and reports no `amr`.
     */
    private val provesNothing: Boolean,
    /**
     * Whether running this enrollment again is how the user changes the credential (a new
     * password, a new number): the new instance then replaces the active one.
     */
    val changeable: Boolean = false,
) {
    /** The method's name, e.g. `"sms"`. */
    val method: String get() = module.method

    /**
     * The step a freshly activated tool session starts on. Must match the `nextStep` of the tool's
     * first `ToolOutcome.InProgress`.
     */
    val startStep: String = startStep ?: role.defaultStartStep

    /**
     * The attributes this tool may assert about its subject, each with its [ClaimSource]. This is
     * the declaration, answerable before any run (offering, planning). The [Claim]s of one concrete
     * run are checked against it by [assertClaimsCovered].
     */
    val claims: Set<ClaimDeclaration> =
        claims.map { ClaimDeclaration(it, vouchedBy ?: ClaimSource(toolId.value)) }.toSet()

    /** The factor kinds this tool provides at most: its module's, none if it proves nothing. */
    val factorTypes: Set<FactorType> = if (provesNothing) emptySet() else module.factorTypes

    /** The highest level this tool can achieve: always its module's. */
    val maxAcr: AcrLevel get() = module.maxAcr

    /**
     * One credential per device ([ToolModule.onePerDevice]): it lives on one caller key, and several
     * instances may coexist on one account.
     */
    val onePerDevice: Boolean get() = module.onePerDevice

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
        !onePerDevice || (module.livesOn(boundKeyRef, callerBindingKeyRef) && linkedAccountId == accountId)

    /** [version] of this tool, or `null` if the server does not serve it. */
    fun inVersion(version: Int): ToolVersion? = ToolVersion(toolId, version).takeIf { version in versions }

    override fun toString(): String = toolId.value
}

/** What a module proves at most: the factor kinds and the level they reach together. */
data class Proves(val factorTypes: Set<FactorType>, val upTo: AcrLevel)

/** [Proves] for [toolModule]: e.g. `factors(POSSESSION, KNOWLEDGE, upTo = AcrLevel.LOA2)`. */
fun factors(vararg factorTypes: FactorType, upTo: AcrLevel): Proves = Proves(factorTypes.toSet(), upTo)

/**
 * Declares one procedure: its method and what a proof of it reaches. Its tools are registered on
 * the result, each as its own value next to it in the module's file:
 * `internal val EnrollQr = QrModule.enroll(ENROLL_QR_TOOL_ID, versions = setOf(1), optInOnly = true)`.
 */
fun toolModule(
    method: String,
    name: Text,
    proves: Proves,
    demoOnly: String? = null,
    onePerDevice: Boolean = false,
    stepData: Map<String, StepDataShape> = emptyMap(),
): ToolModule = ToolModule(
    method = method,
    name = name,
    factorTypes = proves.factorTypes,
    maxAcr = proves.upTo,
    onePerDevice = onePerDevice,
    demoOnly = demoOnly?.let(::DemoOnly),
    stepData = stepData,
)

/**
 * The attributes every identification asserts, so it can be found again by name, first name and
 * date of birth, even after the account is deleted (the change log's search key, ADR-39).
 */
val IDENTIFICATION_FINDABLE_BY: Set<AttributeType> =
    setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES, AttributeType.BIRTH_DATE)

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
     * `auth-qr` pairing, docs/03-tool-architektur.md #4). All other roles act on their own channel.
     * Contributes nothing to its own channel's ACR/AMR and never closes a gap.
     */
    PEER_APPROVAL("input", "approve-%s"),

    /**
     * Attests an attribute the account owns and others depend on (e.g. `confirm-email`): asserts
     * claims, resolves no identity, leaves no credential, adds nothing to ACR/AMR
     * (docs/03-tool-architektur.md #4).
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
