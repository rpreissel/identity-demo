package com.example.identity.contract.tool_api.directory

import java.time.LocalDate

/**
 * Resolves an identification tool's own identifier to the external person id that
 * `ToolOutcome.Completed.Identified` expects - never the person's master data itself.
 */
interface PersonDirectory {
    /**
     * @return the person id (Partnernummer) for this KVNR, or `null` if no matching person is found.
     */
    fun findPersonIdByKvnr(kvnr: String): String?

    /**
     * @return the person id for this Partnernummer (its canonical form, ADR-34), or `null` if it is
     *   malformed or unknown. What a Partner without KVNR identifies by.
     */
    fun findPersonIdByPartnerNumber(partnerNumber: String): String?

    /**
     * Whether the master data for [personId] match every attribute in [claimed]. The answer crosses
     * the port, the master data never does. Names compare in passport (MRZ) form, because each
     * document writes them its own way (`MUELLER` on a chip, `MÜLLER` on an eID card).
     */
    fun matchesMasterData(personId: String, claimed: ClaimedIdentity): Boolean

    /**
     * Whether name and birth date for [personId] match; the narrow sibling of [matchesMasterData]
     * for a procedure that only learns what a person types in (`ident-fsc`).
     */
    fun matchesPersonalDetails(personId: String, familyName: String, givenNames: String, birthDate: LocalDate): Boolean

    /**
     * Whether the register holds another person with the same names and birth date as [personId].
     * Then assigning an attested identity to [personId] needs the address too (ADR-18). The
     * namesake never crosses the port.
     */
    fun hasNamesake(personId: String): Boolean

    /**
     * "Vorname Name" for [personId], or `null` if unknown. A narrow exception to the rule above,
     * only so the demo UI can show who is logged in (`name` ID-token claim), never for a journey
     * decision. It hands out the name only.
     */
    fun displayName(personId: String): String?

    /**
     * The Versicherungsnummer of [personId], or `null`. An identifier, not master data: it becomes
     * the account's `MEMBER_NUMBER` anchor (ADR-34), which is why it may cross the port.
     */
    fun memberNumberOf(personId: String): String?
}

/**
 * The canonical form a KVNR is compared and looked up in: trimmed and uppercased. Shape validation
 * is [Kvnr]'s job.
 */
fun normalizeKvnr(kvnr: String): String = kvnr.trim().uppercase()

/**
 * The attributes a claimed identity (e.g. an eID read) can be verified against. `null` means the
 * attestation did not include it and it is not compared, so a partial attestation verifies
 * against its subset.
 */
data class ClaimedIdentity(
    val familyName: String? = null,
    val givenNames: String? = null,
    val birthDate: LocalDate? = null,
    /** Street and house number in one line, as a document attests it (`AttributeType.STREET_ADDRESS`). */
    val streetAddress: String? = null,
    val postalCode: String? = null,
    val locality: String? = null
)
