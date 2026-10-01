package com.example.identity.core.orchestrator.domain.journey

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.EvidenceAxis
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.contract.tool_api.ToolRole
import com.example.identity.contract.tool_api.ToolId

/*
 * Which account an executed action writes to: the rules that keep a session from being bound to an
 * account it never proved it owns. Pure: every fact comes in as a value, or as a function where the
 * lookup is only needed in one branch. `JourneyActionExecutor` reads, asks here, and writes
 * (docs/adr/ADR-040-fachkern-im-paket-domain.md).
 *
 * A refusal is an [IdentityConflictException] (409): a request that would merge two people or two
 * accounts, not a broken assumption.
 */

/** Where an identification's attested claims land when they resolved to no existing account. */
sealed interface IdentificationTarget {
    /** Nothing in hand: a fresh account, created in the same transaction as its claims. */
    data object NewAccount : IdentificationTarget

    /** The account in hand takes the attestation. */
    data class AccountInHand(val accountId: AccountId) : IdentificationTarget

    companion object {
        /**
         * - **Nothing in hand:** a new account.
         * - **An account without a person binding** takes the attestation (ADR-10). A second account
         *   beside it would silently split one run across two. It must be the same person, though:
         *   a prospect that already attested an identity must not take a second one
         *   ([attestationFits]).
         * - **An identified account** plus an attestation that resolves to nobody means a different
         *   person. Mixing a stranger's identity into it must never happen quietly. Registering on
         *   a linked phone does not land here (`AuthIntent.startsFromDeviceLink`).
         */
        fun forUnresolved(inHand: AccountProfile?, attestationFits: () -> Boolean): IdentificationTarget = when {
            inHand == null -> NewAccount
            inHand.isUnidentified && attestationFits() -> AccountInHand(inHand.accountId)
            else -> throw IdentityConflictException(Text("Die bezeugte Identitaet gehoert nicht zu dem Konto dieser Sitzung"))
        }
    }
}

/**
 * Two accounts meet in one run: the one in hand and the one an identification or attestation
 * resolved to (ADR-20). The disposable account is absorbed into the other one, whichever of the two
 * it is. If neither is disposable, nothing moves.
 */
sealed interface AccountMerge {
    /**
     * The account in hand is disposable (ident first): a placeholder was opened for an attestation
     * that resolved nobody, and the correlation step then found the real account. The session moves
     * over and takes the attestation along. Also the answer when both are disposable, so the anchor
     * that did the resolving stays where the rest of the stock expects it.
     */
    data class MoveInto(val from: AccountId, val into: AccountId) : AccountMerge

    /**
     * The resolved account is disposable ("Enrollment zuerst"): the account in hand holds this
     * run's new credentials, the resolved one is a placeholder left by an abandoned eID run and found
     * again through its `restricted_id` anchor (ADR-19). The session absorbs it; otherwise that
     * leftover would block its own card forever.
     */
    data class AbsorbResolved(val resolved: AccountId, val into: AccountId) : AccountMerge

    companion object {
        /**
         * Neither disposable is refused: two real accounts would merge, which is a decision for an
         * explicit account merge, not a side effect of identification. [resolved] is only looked up
         * when the account in hand is not disposable.
         */
        fun decide(inHand: AccountProfile, resolved: () -> AccountProfile): AccountMerge {
            if (inHand.isDisposable) return MoveInto(from = inHand.accountId, into = resolved().accountId)
            val other = resolved()
            if (other.isDisposable) return AbsorbResolved(resolved = other.accountId, into = inHand.accountId)
            throw IdentityConflictException(Text("Identification claims resolve to a different account"))
        }
    }
}

/**
 * A correlation step (`ToolRole.CORRELATION`, ident-kvnr, ADR-18) proves nothing about the subject.
 * It only turns a typed number into a register-vouched person. Without the [matches] check, attesting
 * yourself and then typing a stranger's number would bind their anchor here.
 *
 * - An account that already has a person is refused: another one would be a change of identity
 *   bought with no proof.
 * - A run that resolved nobody must not pass silently.
 * - The resolved person must match the attested identity. The tool checks this too; failing here
 *   means a tool skipped it.
 */
fun checkCorrelation(account: AccountProfile, toolId: ToolId, claimedPersonId: PartnerNumber?, matches: (PartnerNumber) -> Boolean) {
    if (!account.isUnidentified) {
        throw IdentityConflictException(Text("Dieses Konto ist bereits einer Person zugeordnet"))
    }
    val personId = checkNotNull(claimedPersonId) { "$toolId completed as a correlation without resolving a person" }
    if (!matches(personId)) {
        throw IdentityConflictException(Text("Die Versichertennummer gehoert nicht zu der nachgewiesenen Identitaet"))
    }
}

/**
 * An attested anchor (e.g. a confirmed address) that resolves to another account may move this
 * session there only after a real identification ([EvidenceAxis.IDENTITY]). Otherwise a new
 * "Enrollment zuerst" session could re-confirm somebody else's address and be bound to their account.
 *
 * The attested identity must also fit a target with a register person ([matches], as in ADR-18):
 * owning a mailbox says "this mailbox is mine", not "I am that person".
 */
fun checkAttestationMove(evidence: SessionEvidence, targetPersonId: PartnerNumber?, matches: (PartnerNumber) -> Boolean) {
    if (evidence.methods.none { it.axis == EvidenceAxis.IDENTITY }) {
        throw IdentityConflictException(Text("Diese Adresse gehoert bereits zu einem Konto. Melden Sie sich damit an, statt sich neu zu registrieren."))
    }
    if (targetPersonId != null && !matches(targetPersonId)) {
        throw IdentityConflictException(Text("Diese Adresse gehoert zu einer anderen Person"))
    }
}

/**
 * Which account a proven credential belongs to. Only [ToolRole.ACCOUNT_LOOKUP_AUTH] may name one, because
 * it resolves the account from a submitted identifier. Every other role proves a credential of the
 * account the channel already knows. A named account must still agree with one in hand: nothing
 * legitimately switches accounts mid-journey.
 */
fun accountOfProof(role: ToolRole, namedByTool: AccountId?, inHand: AccountId?): AccountId {
    val named = namedByTool?.takeIf { role == ToolRole.ACCOUNT_LOOKUP_AUTH }
    if (named != null && inHand != null && named != inHand) {
        throw IdentityConflictException(Text("Der Nachweis gehoert zu einem anderen Konto als dieser Sitzung"))
    }
    return checkNotNull(named ?: inHand) { "Authenticated without a known account" }
}
