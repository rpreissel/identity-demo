package com.example.identity.contract.tool_api.claims

import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.contract.tool_api.values.Kvnr
import com.example.identity.contract.tool_api.values.MemberNumber
import com.example.identity.contract.tool_api.values.Email
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.directory.BindingStrength


/**
 * The rules that make some [AttributeType]s anchors, the values accounts are looked up by, and
 * others mere attributes (docs/02-domaenenmodell.md #6, ADR-19).
 */

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
 * open the account to a correlation with somebody else. No default, so a new anchor must decide.
 */
data class AnchorRule(val acrFloor: AnchorAcrFloor, val allowsReplacement: Boolean, val retractableByHolder: Boolean) {
    val bindingStrength: BindingStrength get() = BindingStrength.anchor(allowsReplacement)
}

/**
 * Who owns this attribute's value, in one exhaustive `when`: a new [AttributeType] does not
 * compile until this place decides. `EMAIL` establishes at `loa1`, because a registration has
 * proven nothing yet, but replaces at `loa2`, because that write could hand an account to a new
 * address. The card pseudonyms may change value, never account (ADR-19).
 */
val AttributeType.authority: AttributeAuthority
    get() = when (this) {
        AttributeType.PERSON_ID -> AttributeAuthority.Local(
            AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = false, retractableByHolder = false)
        )
        // Replaced or released when the Personenverzeichnis reports a change (ADR-34).
        AttributeType.MEMBER_NUMBER -> AttributeAuthority.Local(
            AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = false)
        )
        AttributeType.EID_RESTRICTED_ID,
        AttributeType.NECT_RESTRICTED_ID -> AttributeAuthority.Local(
            AnchorRule(AnchorAcrFloor(AcrLevel.LOA2, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = false)
        )
        AttributeType.EMAIL -> AttributeAuthority.Local(
            AnchorRule(AnchorAcrFloor(AcrLevel.LOA1, AcrLevel.LOA2), allowsReplacement = true, retractableByHolder = true)
        )
        AttributeType.KVNR,
        AttributeType.FAMILY_NAME,
        AttributeType.GIVEN_NAMES,
        AttributeType.BIRTH_DATE,
        AttributeType.STREET_ADDRESS,
        AttributeType.POSTAL_CODE,
        AttributeType.LOCALITY -> AttributeAuthority.PersonDirectory
        AttributeType.PHONE_NUMBER,
        // MethodModule claims are retracted with the method instance, which makes the
        // dependency on the password work (ADR-24).
        AttributeType.PASSWORD_EXISTS -> AttributeAuthority.MethodModule
    }

/** The anchor rules of this attribute type, or `null` for a type the account does not own. */
val AttributeType.anchorRule: AnchorRule?
    get() = (authority as? AttributeAuthority.Local)?.anchor

/** Whether the account itself owns this attribute's value - i.e. whether it is a local anchor. */
val AttributeType.isLocalAnchor: Boolean
    get() = authority is AttributeAuthority.Local

/**
 * Canonical form of [value], applied identically on write and on lookup. An invalid value throws
 * instead of falling back to a weaker match. A non-anchor type is a contract error.
 */
fun AttributeType.normalizeAnchorValue(value: String): String = when (this) {
    AttributeType.PERSON_ID -> PartnerNumber.of(value).value
    AttributeType.EID_RESTRICTED_ID,
    AttributeType.NECT_RESTRICTED_ID -> value.trim()
    AttributeType.MEMBER_NUMBER -> MemberNumber.of(value).value
    AttributeType.EMAIL -> Email.of(value).value
    AttributeType.KVNR -> {
        // Validate the format first, so a malformed value fails like any other bad input.
        // Then refuse: KVNR resolves live through PersonDirectory, never as a local anchor.
        Kvnr.of(value)
        error("$this is not a local account anchor - authority is $authority, resolved live via PersonDirectory")
    }
    AttributeType.FAMILY_NAME,
    AttributeType.GIVEN_NAMES,
    AttributeType.BIRTH_DATE,
    AttributeType.STREET_ADDRESS,
    AttributeType.POSTAL_CODE,
    AttributeType.LOCALITY,
    AttributeType.PHONE_NUMBER,
    AttributeType.PASSWORD_EXISTS ->
        error("$this is not an anchor attribute (authority: $authority), has no normalized anchor value")
}
