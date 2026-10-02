package com.example.identity.contract.tool_api.claims

import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.Tool

/**
 * Who established a [Claim]: the register ([PERSON_DIRECTORY]), a tool run (named by its [ToolId]), or
 * only the user ([SELF_REPORTED]).
 */
@JvmInline
value class ClaimSource(val value: String) {
    override fun toString(): String = value

    companion object {
        /** The master-data backend (personenverzeichnis) - strongest claim trust. */
        val PERSON_DIRECTORY = ClaimSource("person_directory")

        /** A value the user entered with nothing backing it. */
        val SELF_REPORTED = ClaimSource("self-reported")
    }
}

/**
 * The trust classes of a [ClaimSource]. Higher [rank] wins; recency only breaks ties within one
 * level (docs/02-domaenenmodell.md #6).
 */
enum class ClaimTrust(val rank: Int) {
    /** Backed by the master-data backend, e.g. personenverzeichnis. */
    AUTHORITATIVE(3),
    /** Proven by a tool run, e.g. an eID procedure or a confirmed email-code exchange. */
    PROVEN(2),
    /** The user vouched for it, nothing else. */
    SELF_REPORTED(1)
}

/** The [ClaimTrust] this [ClaimSource] belongs to. */
val ClaimSource.claimTrust: ClaimTrust
    get() = when (this) {
        ClaimSource.PERSON_DIRECTORY -> ClaimTrust.AUTHORITATIVE
        ClaimSource.SELF_REPORTED -> ClaimTrust.SELF_REPORTED
        else -> ClaimTrust.PROVEN
    }

/**
 * One attribute value a completed tool run asserts, with who established it and at what assurance.
 * Covered by the tool's [Tool.claims], at most one per [AttributeType]
 * (docs/02-domaenenmodell.md #6).
 */
data class Claim(
    /** Which attribute is being asserted. */
    val attributeType: AttributeType,
    /** The asserted value, unnormalized - normalization for anchor lookups happens in `account`. */
    val value: String,
    /** Who vouches for [value] - decides the [ClaimTrust] via [ClaimSource.claimTrust]. */
    val source: ClaimSource,
    /**
     * The level the session had proven when this claim was established (ADR-5). `null` if unknown;
     * the claim still counts but can never raise a level by itself.
     */
    val establishedAcr: AcrLevel? = null
)

/**
 * Value invariants every [Claim] must satisfy before resolution or persistence. A violation is a
 * programming error, so it throws.
 */
fun Claim.validateValue() {
    check(value.isNotBlank()) {
        "${attributeType.wireName} claim must not be blank"
    }
    check(attributeType.acceptsValue(value)) {
        "${attributeType.wireName} claim has an invalid value"
    }
}

/** One entry of [Tool.requires]: [attributeType] at no less than [minClaimTrust]. */
data class ClaimRequirement(
    val attributeType: AttributeType,
    val minClaimTrust: ClaimTrust
)

/**
 * One entry of [Tool.claims]: an [AttributeType] and the [ClaimSource] a run asserts it
 * with. No [ClaimTrust] here: it follows from the source via [ClaimSource.claimTrust], which is
 * global policy, not per-tool knowledge.
 */
data class ClaimDeclaration(
    val attributeType: AttributeType,
    val source: ClaimSource
)

/**
 * Checks the [Claim]s of one run against [Tool.claims]: each declared with the same
 * source, at most one per attribute (a claim set is a snapshot, docs/02-domaenenmodell.md #6).
 * A mismatch is a programming error and fails the transaction instead of recording an assertion
 * the catalog never promised.
 */
fun assertClaimsCovered(tool: Tool, claims: List<Claim>) {
    val declared = tool.claims.associateBy { it.attributeType }
    val seen = mutableSetOf<AttributeType>()
    claims.forEach { claim ->
        claim.validateValue()
        val declaration = checkNotNull(declared[claim.attributeType]) {
            "${tool.toolId} reported a ${claim.attributeType.wireName} claim but declares none"
        }
        check(declaration.source == claim.source) {
            "${tool.toolId} reported ${claim.attributeType.wireName} with source ${claim.source}, but declares ${declaration.source}"
        }
        check(seen.add(claim.attributeType)) {
            "${tool.toolId} reported more than one claim for ${claim.attributeType.wireName}"
        }
    }
}
