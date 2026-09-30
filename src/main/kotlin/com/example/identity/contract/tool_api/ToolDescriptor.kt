package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.claims.ClaimRequirement
import com.example.identity.contract.tool_api.claims.ClaimDeclaration
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.texts.Text

/**
 * A tool's public identifier ("auth-sms", "enroll-password", ...). A toolId names one concrete
 * procedure, a method name ("sms") names the credential family several tools share. The value class
 * keeps the two from being mixed up like two `String`s could be.
 */
@JvmInline
value class ToolId(val value: String) {
    override fun toString(): String = value
}

/**
 * A tool's self-description, implemented once per tool. All implementations in the application
 * context together are the tool catalog; there is no central list.
 */
interface ToolDescriptor {
    /** The tool's public, stable identifier, e.g. `"auth-sms"`. Never derived from other fields. */
    val toolId: ToolId

    /**
     * The credential family, e.g. `"sms"`. Shared by all tools that play different [ToolRole]s
     * for the same credential: `enroll-sms`, `auth-sms` and `auth-sms-lookup` all use `"sms"`.
     */
    val method: String

    /** The role this tool plays for [method]. See [ToolRole]. */
    val role: ToolRole

    /**
     * The step a freshly activated tool session starts on. Must match the `nextStep` of the tool's
     * first `ToolOutcome.InProgress`.
     */
    val startStep: String get() = role.defaultStartStep

    /**
     * Non-`null` if activating this tool completes it at once, without step or input (e.g.
     * `enroll-email`, whose address was proven before). The text says what choosing it does.
     * Such a tool is never started automatically, even as the only candidate, because it would
     * change the account without the user having chosen anything. It is offered on the selection
     * page with this text instead.
     */
    val completesOnActivation: Text? get() = null

    /** The factor kinds this tool can provide at most. */
    val factorTypes: Set<FactorType>

    /** The highest level this tool can achieve, e.g. [AcrLevel.LOA2]. */
    val maxAcr: AcrLevel

    /**
     * The attributes this tool may assert about its subject, each with its [ClaimSource]. This is
     * the declaration, answerable before any run (offering, planning). The [Claim]s of one concrete
     * run are checked against it by [assertClaimsCovered].
     */
    val claims: Set<ClaimDeclaration>
        get() = emptySet()

    /**
     * What the account must already have for this tool to be offered: each [AttributeType] at no
     * less than its [ClaimTrust], checked against the consolidated value including retractions
     * (ADR-12). E.g. "confirmed email" is `ClaimRequirement(EMAIL, PROVEN)`.
     * It is a standing precondition: when a requirement stops being established, the credential is
     * revoked as well. That is how one method depends on another (ADR-24).
     */
    val requires: Set<ClaimRequirement>
        get() = emptySet()

    /**
     * True if several active instances of this method can coexist on one account (e.g. one per
     * device). By default a new enrollment replaces the active one.
     */
    val allowsMultipleInstances: Boolean
        get() = false

    /**
     * Non-`null` if the credential lives on one physical caller key (a non-extractable device key).
     * It is then usable only from that caller and worthless once the key is bound to another
     * account (docs/09-dpop.md). Independent of [allowsMultipleInstances]. A [CallerKeyBinding]
     * instead of a flag, so key-boundness cannot be declared without the rule that tells instances
     * apart (docs/03-tool-architektur.md #1).
     */
    val keyBinding: CallerKeyBinding?
        get() = null

    /**
     * Non-`null` if the method can name the instance on a caller's key in a showable way (the
     * device key, the KOBIL phone identifier). Only meaningful together with [keyBinding]. The
     * instance details are opaque to everyone but the owning module, so only the module can pick
     * a showable value. It discloses that one value, not the details map, which may hold hashes
     * and binding keys.
     */
    val instanceDisclosure: InstanceDisclosure?
        get() = null

    /**
     * Set when this tool vouches for something it cannot prove (`auth-device` believes the user
     * verification a device proof merely claims). Outside `demo.mode` such a tool is neither
     * offered nor activatable, and no setting switches it on (ADR-36). A real counterpart is a
     * tool of its own.
     */
    val demoOnly: DemoOnly?
        get() = null

    /**
     * Is this credential usable by this caller right now? A key-bound one must live on the caller's
     * key, and the device must still be linked to the credential's account: a device is bound to
     * one account at a time ([com.example.identity.core.orchestrator.session.DeviceAccountLink],
     * docs/09-dpop.md). The second half is the same for every method, so it lives here.
     */
    fun usableByCaller(instanceDetails: Map<String, Any?>?, callerBindingKeyRef: String?, linkedAccountId: Long?, accountId: Long): Boolean {
        val binding = keyBinding ?: return true
        return binding.livesOn(instanceDetails, callerBindingKeyRef) && linkedAccountId == accountId
    }
}

/** Why a tool is only fit for a demonstration ([ToolDescriptor.demoOnly]) - stated, so it can be reviewed. */
data class DemoOnly(val reason: String)

/**
 * How a method turns its own instance details into one showable reference - see
 * [ToolDescriptor.instanceDisclosure].
 */
fun interface InstanceDisclosure {
    /**
     * A short, showable reference for the instance, or `null`. Never a credential: it goes to a
     * client that has only proven possession of the key this instance lives on.
     */
    fun referenceOf(instanceDetails: Map<String, Any?>?): String?
}

/**
 * How a tool tells its own credential instances apart by the caller key each one lives on - see
 * [ToolDescriptor.keyBinding]. Generic code asks this to pick the instance on the key in hand;
 * only the owning module knows its detail keys.
 */
fun interface CallerKeyBinding {
    /**
     * Does the active instance described by [instanceDetails] live on [callerBindingKeyRef]?
     * The details were written by the owning module and are opaque to everyone else. An instance
     * without details lives on no particular key.
     */
    fun livesOn(instanceDetails: Map<String, Any?>?, callerBindingKeyRef: String?): Boolean
}

/**
 * The role a tool plays for its [ToolDescriptor.method]. `(method, role)` identifies a concrete
 * procedure. Decisions ask for the role itself, never a coarser grouping: identification and
 * correlation look alike but only identification proves anything.
 */
enum class ToolRole(val defaultStartStep: String) {
    /** Resolves identity (e.g. `ident-fsc`). Never establishes a durable credential. */
    IDENTIFICATION("input"),

    /**
     * Attaches an already attested identity to its register person (e.g. `ident-kvnr` with the
     * Versichertennummer, ADR-18). It proves nothing by itself: the user supplies a mere
     * identifier. So it is never offered as a way to identify, records no evidence, and the act
     * is only accepted if the person matches what the account had attested. [ToolDescriptor.maxAcr]
     * describes the attestation the step rests on, not what typing the number proved.
     */
    CORRELATION("input"),

    /** Creates a new credential for the method (e.g. `enroll-sms`). */
    ENROLLMENT("enroll"),

    /** Proves a credential for an account the channel already knows (e.g. `auth-sms`). */
    KNOWN_ACCOUNT_AUTH("auth"),

    /**
     * Proves the same credential as its [KNOWN_ACCOUNT_AUTH] sibling, but resolves the account from a
     * submitted identifier (e.g. `auth-sms-lookup`).
     */
    ACCOUNT_LOOKUP_AUTH("auth"),

    /**
     * Approves or declines a pending request from another channel (e.g. `confirm-qr-login` deciding
     * an `auth-qr` pairing, docs/03-tool-architektur.md). All other roles act on their own channel.
     * Contributes nothing to its own channel's ACR/AMR and never closes a gap.
     */
    PEER_APPROVAL("input"),

    /**
     * Attests an attribute the account owns and others depend on (e.g. `confirm-email`): asserts
     * claims, resolves no identity, leaves no credential, adds nothing to ACR/AMR
     * (docs/03-tool-architektur.md #2).
     */
    ATTESTATION("input")
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
