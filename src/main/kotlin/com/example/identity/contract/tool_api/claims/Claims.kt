package com.example.identity.contract.tool_api.claims

import com.example.identity.contract.tool_api.values.Partnernr
import com.example.identity.contract.tool_api.ToolId
import com.example.identity.contract.tool_api.ToolDescriptor
import java.time.LocalDate

/**
 * A kind of identifying attribute a tool can assert about its subject. A closed enum on purpose:
 * it appears on both sides of the tool contract ([ToolDescriptor.claims] and the [Claim]s of a
 * run), and a plain String would let `"emial"` compile.
 */
enum class AttributeType(val wireName: String) {
    /** The person row this account belongs to, as the master-data backend's (personenverzeichnis) PK. */
    PERSON_ID("person_id"),
    /** Krankenversichertennummer - the anchor a person is resolved by in the master data. */
    KVNR("kvnr"),
    /**
     * Versicherungsnummer - eight digits, only for a person insured with us. Kept by the
     * Personenverzeichnis (changeable there); when it exists it is also a local account anchor,
     * replaced whenever the Personenverzeichnis reports a new one (ADR-34).
     */
    INSURANCE_NUMBER("insurance_number"),
    /**
     * Card-bound pseudonym from the eID read (stand-in for the real "Restricted Identifier"). It
     * changes with a new card but never moves to another person, so it recognizes an
     * eid-identified Interessent: a replaceable local account anchor (ADR-19). The register never
     * stores it.
     */
    EID_RESTRICTED_ID("restricted_id"),
    /**
     * The card pseudonym from an eID read through Nect. The pseudonym is specific to card and
     * service provider (§18 PAuswG), so it never equals [EID_RESTRICTED_ID] for the same card.
     * Its own anchor with the same rules; neither kind of run overwrites the other's anchor.
     */
    NECT_RESTRICTED_ID("nect_restricted_id"),
    /** Family name. Master-data field for a bound account, attested history in the claim log. */
    FAMILY_NAME("family_name"),
    /** Given name(s). Master-data field, same rule as [FAMILY_NAME]. */
    GIVEN_NAMES("given_names"),
    /** ISO date, e.g. `1970-01-01`. Master-data field: delegated, never projected (ADR notes in
     *  docs/02-domaenenmodell.md #6). */
    BIRTH_DATE("birth_date"),
    /**
     * Street and house number in one line, as documents attest it (eID `Street`, BSI TR-03130;
     * EUDI PID `address.street_address`). The register keeps the two apart and joins them at its
     * own boundary (docs/08-projektrahmen.md P-4). Like [POSTAL_CODE] and [LOCALITY] a master-data
     * field like [BIRTH_DATE].
     */
    STREET_ADDRESS("street_address"),
    /** Postal code. */
    POSTAL_CODE("postal_code"),
    /** City/town. */
    LOCALITY("locality"),
    /**
     * E-mail address. Unlike the other attributes, a value the account owns itself
     * (`AttributeAuthority.Local`): `confirm-email` establishes it, lookup tools resolve an account
     * through it, and a retraction deletes its anchor row (ADR-12).
     */
    EMAIL("email"),
    /** Mobile number, established by an `enroll-sms` run - the address a TAN is delivered to. */
    PHONE_NUMBER("phone_number"),

    /**
     * "This account holds a password credential", established by `enroll-password` and retracted
     * with it. A fact about the credentials, not the person. It lets another method depend on the
     * password with a plain `requires` (ADR-24). Its value is the constant [PASSWORD_EXISTS_MARKER].
     */
    PASSWORD_EXISTS("password_exists");

    companion object {
        /** Reverse of [wireName]; `null` lets a caller facing outside input refuse an unknown name. */
        fun fromWireName(wireName: String): AttributeType? = entries.firstOrNull { it.wireName == wireName }
    }
}

/**
 * Who established a [Claim]: the register ([PERSON_DIRECTORY]), a tool run ([of] a [ToolId]), or
 * only the user ([SELF_REPORTED]).
 */
@JvmInline
value class ClaimSource(val value: String) {
    override fun toString(): String = value

    companion object {
        /** The master-data backend (personenverzeichnis) - strongest trust level. */
        val PERSON_DIRECTORY = ClaimSource("person_directory")

        /** A value the user entered with nothing backing it. */
        val SELF_REPORTED = ClaimSource("self-reported")

        /** A claim established by a concrete tool run, e.g. an eID procedure. */
        fun of(toolId: ToolId): ClaimSource = ClaimSource(toolId.value)
    }
}

/**
 * The trust classes of a [ClaimSource]. Higher [rank] wins; recency only breaks ties within one
 * level (docs/02-domaenenmodell.md #6).
 */
enum class TrustLevel(val rank: Int) {
    /** Backed by the master-data backend, e.g. personenverzeichnis. */
    AUTHORITATIVE(3),
    /** Proven by a tool run, e.g. an eID procedure or a confirmed email-code exchange. */
    PROVEN(2),
    /** The user vouched for it, nothing else. */
    SELF_REPORTED(1)
}

/** The [TrustLevel] this [ClaimSource] belongs to. */
val ClaimSource.trustLevel: TrustLevel
    get() = when (this) {
        ClaimSource.PERSON_DIRECTORY -> TrustLevel.AUTHORITATIVE
        ClaimSource.SELF_REPORTED -> TrustLevel.SELF_REPORTED
        else -> TrustLevel.PROVEN
    }

/** The only value of an [AttributeType.PASSWORD_EXISTS] claim; the claim itself is the statement. */
const val PASSWORD_EXISTS_MARKER = "true"

/**
 * One attribute value a completed tool run asserts, with who established it and at what assurance.
 * Covered by the descriptor's [ToolDescriptor.claims], at most one per [AttributeType]
 * (docs/02-domaenenmodell.md #6).
 */
data class Claim(
    /** Which attribute is being asserted. */
    val attributeType: AttributeType,
    /** The asserted value, unnormalized - normalization for anchor lookups happens in `account`. */
    val value: String,
    /** Who vouches for [value] - decides the [TrustLevel] via [ClaimSource.trustLevel]. */
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
    when (attributeType) {
        AttributeType.PERSON_ID -> check(Partnernr.ofOrNull(value) != null) {
            "person_id claim must be a Partnernummer (P and nine digits)"
        }
        AttributeType.BIRTH_DATE -> check(runCatching { LocalDate.parse(value.trim()) }.isSuccess) {
            "geburtsdatum claim must be an ISO date"
        }
        else -> Unit
    }
}

/** One entry of [ToolDescriptor.requires]: [attributeType] at no less than [minTrustLevel]. */
data class ClaimRequirement(
    val attributeType: AttributeType,
    val minTrustLevel: TrustLevel
)

/**
 * One entry of [ToolDescriptor.claims]: an [AttributeType] and the [ClaimSource] a run asserts it
 * with. No [TrustLevel] here: it follows from the source via [ClaimSource.trustLevel], which is
 * global policy, not per-tool knowledge.
 */
data class ClaimDeclaration(
    val attributeType: AttributeType,
    val source: ClaimSource
)

/**
 * Checks the [Claim]s of one run against [ToolDescriptor.claims]: each declared with the same
 * source, at most one per attribute (a claim set is a snapshot, docs/02-domaenenmodell.md #6).
 * A mismatch is a programming error and fails the transaction instead of recording an assertion
 * the catalog never promised.
 */
fun assertClaimsCovered(descriptor: ToolDescriptor, claims: List<Claim>) {
    val declared = descriptor.claims.associateBy { it.attributeType }
    val seen = mutableSetOf<AttributeType>()
    claims.forEach { claim ->
        claim.validateValue()
        val declaration = checkNotNull(declared[claim.attributeType]) {
            "${descriptor.toolId} reported a ${claim.attributeType.wireName} claim but declares none"
        }
        check(declaration.source == claim.source) {
            "${descriptor.toolId} reported ${claim.attributeType.wireName} with source ${claim.source}, but declares ${declaration.source}"
        }
        check(seen.add(claim.attributeType)) {
            "${descriptor.toolId} reported more than one claim for ${claim.attributeType.wireName}"
        }
    }
}
