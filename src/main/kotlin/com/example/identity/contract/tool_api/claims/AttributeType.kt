package com.example.identity.contract.tool_api.claims

import com.example.identity.contract.tool_api.values.Email
import com.example.identity.contract.tool_api.values.Kvnr
import com.example.identity.contract.tool_api.values.MemberNumber
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * A kind of identifying attribute a tool can assert about its subject, together with the rules
 * that make it an anchor or a mere attribute (docs/02-domaenenmodell.md #6, ADR-19). It appears on
 * both sides of the tool contract (a tool's `claims` and the [Claim]s of a run), so it is a type,
 * not a plain String that would let `"emial"` compile.
 *
 * Built only by the factories below, which have no defaults for the rules: a new attribute does
 * not exist until it decides who owns its value. The vocabulary the orchestrator and the
 * Personenverzeichnis share lives here; an attribute only one procedure produces (a card pseudonym,
 * a phone number) is declared in that procedure's module, so introducing one never touches the core.
 */
class AttributeType private constructor(
    /** The stable name stored and sent for this attribute, e.g. `"email"`. */
    @get:JsonValue val wireName: String,
    /** Who owns the current value: the account (an anchor), the register, or a method module. */
    val authority: AttributeAuthority,
    /** Compared as written; otherwise values are compared case-insensitively (claim log, anchors). */
    val caseSensitive: Boolean,
    private val canonical: ((String) -> String)?,
    private val format: ((String) -> Any?)?,
    private val valueCheck: ((String) -> Boolean)?,
) {
    init {
        val previous = registry.putIfAbsent(wireName, this)
        check(previous == null) { "Attribute '$wireName' is declared twice" }
    }

    /** The anchor rules of this attribute, or `null` for one the account does not own. */
    val anchorRule: AnchorRule? get() = (authority as? AttributeAuthority.Local)?.anchor

    /** Whether the account itself owns this attribute's value - i.e. whether it is a local anchor. */
    val isLocalAnchor: Boolean get() = authority is AttributeAuthority.Local

    /**
     * Canonical form of [value], applied identically on write and on lookup. An invalid value
     * throws instead of falling back to a weaker match. A non-anchor type is a contract error.
     */
    fun normalizeAnchorValue(value: String): String {
        canonical?.let { return it(value) }
        // Validate the format first, so a malformed value fails like any other bad input.
        format?.let { requireNotNull(it(value)) { "Invalid $wireName" } }
        error("$this is not an anchor attribute (authority: $authority), has no normalized anchor value")
    }

    /** Whether [value] has the form this attribute's claims must have (e.g. a Partnernummer). */
    fun acceptsValue(value: String): Boolean = valueCheck?.invoke(value) ?: true

    override fun toString(): String = wireName

    companion object {
        private val registry = ConcurrentHashMap<String, AttributeType>()

        /**
         * An attribute the account owns: its value is an anchor in `account.anchor`, unique, and
         * resolves lookups. [normalize] gives the canonical form; [caseSensitive] whether case counts.
         */
        fun anchor(
            wireName: String,
            acrFloor: AnchorAcrFloor,
            allowsReplacement: Boolean,
            retractableByHolder: Boolean,
            caseSensitive: Boolean,
            normalize: (String) -> String,
            valueCheck: ((String) -> Boolean)? = null,
        ): AttributeType = AttributeType(
            wireName, AttributeAuthority.Local(AnchorRule(acrFloor, allowsReplacement, retractableByHolder)),
            caseSensitive, normalize, null, valueCheck,
        )

        /**
         * An attribute the Personenverzeichnis owns and the orchestrator reads live, never projected
         * locally. [format] checks a value before an anchor use of it is refused.
         */
        fun fromDirectory(
            wireName: String,
            format: ((String) -> Any?)? = null,
            valueCheck: ((String) -> Boolean)? = null,
        ): AttributeType = AttributeType(wireName, AttributeAuthority.PersonDirectory, false, null, format, valueCheck)

        /**
         * An attribute the method module that enrolled it owns, in its own `<module>.enrollment` row.
         * Its claims are retracted with the method instance, which lets another method depend on it
         * through `requires` (ADR-24).
         */
        fun ownedByMethod(wireName: String): AttributeType =
            AttributeType(wireName, AttributeAuthority.MethodModule, false, null, null, null)

        /** Reverse of [wireName]; `null` lets a caller facing outside input refuse an unknown name. */
        fun fromWireName(wireName: String): AttributeType? = registry[wireName]

        /** Reverse of [wireName] for stored or sent data, where an unknown name is corrupt. */
        @JvmStatic
        @JsonCreator
        fun of(wireName: String): AttributeType =
            checkNotNull(fromWireName(wireName)) { "Unknown attribute '$wireName'" }

        /** Every attribute declared so far: the shared vocabulary plus those of every module loaded. */
        val declared: Collection<AttributeType> get() = registry.values

        /** The person row this account belongs to, as the master-data backend's (personenverzeichnis) PK. Never replaced. */
        val PERSON_ID = anchor(
            "person_id", AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = false, retractableByHolder = false,
            caseSensitive = false,
            normalize = { requireNotNull(PartnerNumber.parse(it)) { "Invalid partner number" }.value },
            valueCheck = { PartnerNumber.parse(it) != null },
        )

        /** Krankenversichertennummer - the anchor a person is resolved by in the master data, read live there. */
        val KVNR = fromDirectory("kvnr", format = { Kvnr.parse(it) })

        /**
         * Versicherungsnummer - eight digits, only for a person insured with us. Kept by the
         * Personenverzeichnis (changeable there); when it exists it is also a local account anchor,
         * replaced or released whenever the Personenverzeichnis reports a change (ADR-34).
         */
        val MEMBER_NUMBER = anchor(
            "member_number", AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = false,
            caseSensitive = false,
            normalize = { requireNotNull(MemberNumber.parse(it)) { "Invalid member number" }.value },
        )

        /** Family name. Master-data field for a bound account, attested history in the claim log. */
        val FAMILY_NAME = fromDirectory("family_name")

        /** Given name(s). Master-data field, same rule as [FAMILY_NAME]. */
        val GIVEN_NAMES = fromDirectory("given_names")

        /** ISO date, e.g. `1970-01-01`. Master-data field: delegated, never projected (docs/02-domaenenmodell.md #6). */
        val BIRTH_DATE = fromDirectory("birth_date", valueCheck = { runCatching { LocalDate.parse(it.trim()) }.isSuccess })

        /**
         * Street and house number in one line, as documents attest it (eID `Street`, BSI TR-03130;
         * EUDI PID `address.street_address`). The register keeps the two apart and joins them at its
         * own boundary (docs/08-projektrahmen.md P-4). Like [POSTAL_CODE] and [LOCALITY] a master-data
         * field like [BIRTH_DATE].
         */
        val STREET_ADDRESS = fromDirectory("street_address")

        /** Postal code. */
        val POSTAL_CODE = fromDirectory("postal_code")

        /** City/town. */
        val LOCALITY = fromDirectory("locality")

        /**
         * E-mail address. Unlike the register's attributes, a value the account owns itself:
         * `confirm-email` establishes it, lookup tools resolve an account through it, and a
         * retraction deletes its anchor row (ADR-12). Established at loa1, because a registration has
         * proven nothing yet, but replaced only at loa2, because that write could hand an account to
         * a new address. The holder may withdraw it in self-service.
         */
        val EMAIL = anchor(
            "email", AnchorAcrFloor(AcrLevel.LOA1, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = true,
            caseSensitive = false,
            normalize = { requireNotNull(Email.parse(it)) { "Invalid email address" }.value },
        )
    }
}

/**
 * Who owns the current value of an attribute. "The account owns it" and "it is an anchor" are
 * one fact, so [Local] carries its [AnchorRule] and the unpaired state cannot be written down.
 */
sealed interface AttributeAuthority {
    /** The account owns the value in `account.anchor`, which is unique and resolves every lookup. */
    data class Local(val anchor: AnchorRule) : AttributeAuthority

    /**
     * Owned by the register and read live through [PersonDirectory][com.example.identity.contract.tool_api.directory.PersonDirectory],
     * never projected locally, so it cannot go stale. Local claim-log rows are history only.
     */
    data object PersonDirectory : AttributeAuthority

    /**
     * Owned by the method module that enrolled it, in its own `<module>.enrollment` row (e.g.
     * `auth_sms.enrollment.phone_number`). The `account` module never resolves it.
     */
    data object MethodModule : AttributeAuthority
}

/**
 * The assurance an anchor write needs. [establish] is the first binding of a value; [replace]
 * re-points an account that already resolves by another value. Replacing is the attack, since
 * every lookup login resolves through the anchor.
 */
data class AnchorAcrFloor(val establish: AcrLevel, val replace: AcrLevel)

/**
 * The rules of an [AttributeAuthority.Local] attribute. [bindingStrength] follows from
 * [allowsReplacement]. [retractableByHolder] says whether the holder may withdraw the value in
 * self-service: a contact channel yes, an identity anchor no, because withdrawing PERSON_ID would
 * open the account to a correlation with somebody else.
 */
data class AnchorRule(val acrFloor: AnchorAcrFloor, val allowsReplacement: Boolean, val retractableByHolder: Boolean) {
    val bindingStrength: com.example.identity.contract.tool_api.directory.BindingStrength
        get() = com.example.identity.contract.tool_api.directory.BindingStrength.anchor(allowsReplacement)
}
