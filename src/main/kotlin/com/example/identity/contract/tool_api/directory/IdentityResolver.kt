package com.example.identity.contract.tool_api.directory

import com.example.identity.contract.tool_api.claims.anchorRule
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.texts.Text

/**
 * Central identity resolution: do the claims a tool just attested belong to an existing account?
 * Tools attest and never see accounts, the account module answers, the orchestrator governs the
 * consequences (docs/02-domaenenmodell.md #6). One matching policy for every procedure. It runs
 * on anchor values only; name plus birth date is ambiguous and never matched on its own (ADR-19).
 */
interface IdentityResolver {
    /**
     * Which account, if any, the just-attested [claims] belong to. Only anchor claims (every
     * `AttributeAuthority.Local` type) take part; the rest are never matched on.
     *
     * @return [Resolution.ExistingAccount] when an anchor resolved, else [Resolution.Unresolved].
     * @throws IdentityConflictException if claims contradict the person record behind their own
     * anchor, so a contradiction is never mistaken for a match.
     */
    fun resolve(claims: Set<Claim>): Resolution

    /**
     * Does the register's person [personId] match what account [accountId] has already attested
     * (name, given name, birth date)? Guards a correlation step like `ident-kvnr` (ADR-18):
     * otherwise typing a stranger's number would bind the stranger's anchor to your account.
     * `false` if the account attested nothing, since an empty comparison would succeed vacuously.
     */
    fun attestedIdentityMatches(accountId: Long, personId: String): Boolean

    /**
     * May account [accountId] take these attested [claims] without becoming somebody else? `true`
     * if it has attested no identity yet, or the claims name the same person (compared in passport
     * form, ignoring case, umlaut spelling and diacritics). Applies to an Interessent too (ADR-18).
     */
    fun attestationFits(accountId: Long, claims: Set<Claim>): Boolean
}

/** Result of resolving attested claims against the existing account stock. Never a boolean. */
sealed interface Resolution {
    /** An existing account owns (part of) these claims; [matchedVia] is mandatory provenance. */
    data class ExistingAccount(val accountId: Long, val matchedVia: MatchedVia) : Resolution

    /** Nothing in the stock matches - the identified subject has no account yet. */
    object Unresolved : Resolution
}

/**
 * How strongly a resolution match binds an identity. An anchor nothing can replace (PERSON_ID)
 * beats one a later, equally strong proof may re-point (EMAIL, the eID card pseudonym). Derived
 * from [AnchorRule.allowsReplacement], not chosen separately.
 */
enum class BindingStrength {
    /** A unique anchor whose value a later, equally strong proof may replace (e.g. EMAIL). */
    REPLACEABLE_ANCHOR,
    /** A unique anchor that, once bound, is never replaced (e.g. PERSON_ID) - the strongest possible match. */
    IMMUTABLE_ANCHOR;

    companion object {
        /** Derives the strength from [AnchorRule.allowsReplacement], so the two cannot disagree. */
        fun anchor(allowsReplacement: Boolean) = if (allowsReplacement) REPLACEABLE_ANCHOR else IMMUTABLE_ANCHOR
    }
}

/** How a resolution matched, carrying its [BindingStrength] for [Resolution.ExistingAccount]. */
sealed interface MatchedVia {
    /** How strongly this particular match binds - what an upgrade decision is made on. */
    val bindingStrength: BindingStrength

    /** A unique anchor value matched via `account.anchor`. */
    data class Anchor(val attributeType: AttributeType) : MatchedVia {
        override val bindingStrength = checkNotNull(attributeType.anchorRule) {
            "$attributeType is not an anchor attribute, has no binding strength"
        }.bindingStrength
    }
}

/**
 * A tool-attested claim contradicts the person record behind its own anchor, e.g. eID claims that
 * do not match the person the KVNR resolves to. Register-attested claims were checked at the
 * source. The orchestrator surfaces it as an invalid journey state, so the journey log keeps the
 * decision point.
 */
class IdentityConflictException(val text: Text, detail: String? = null) :
    RuntimeException(detail?.let { "${text.template} ($it)" } ?: text.template)
