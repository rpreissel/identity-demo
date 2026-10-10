package com.example.identity.tools.ident_eid.internal

import com.example.identity.contract.tool_api.directory.ClaimedIdentity
import com.example.identity.contract.tool_api.ToolStep
import java.security.MessageDigest
import java.time.LocalDate
import com.example.identity.contract.tool_api.MissingFields

/**
 * Pure state of the ident-eid flow (docs/03-tool-architektur.md #6): one step whose
 * [IdentEidFlow.missingFields] come staged, card data first, then the PIN. Only what the simulated
 * card carries: no KVNR and no person reference (ADR-18). The card's restricted identifier is
 * attested as the recognition anchor (ADR-19).
 */
internal data class IdentEidState(
    val familyName: String? = null,
    val givenNames: String? = null,
    val birthDate: LocalDate? = null,
    /** Street and house number in one line - the card's `Street` carries both. */
    val streetAddress: String? = null,
    val postalCode: String? = null,
    val locality: String? = null,
    val restrictedId: String? = null,
    val pinHash: String? = null
)

/**
 * What [IdentEidFlow.decide] concluded from a merged state. The card data is checked the moment it
 * is complete, before the PIN is even asked for - so the stored card data is only ever incomplete
 * or already checked, and no flag has to say which.
 */
internal sealed interface IdentEidDecision {
    data object Incomplete : IdentEidDecision

    /** The card data was just completed or changed and fails the format checks. */
    data object CardRejected : IdentEidDecision

    /** The card data stands checked and a PIN is present: check the PIN. */
    data class VerifyPin(val claimed: ClaimedIdentity, val restrictedId: String, val pinHash: String) : IdentEidDecision
}

internal object IdentEidFlow {

    /** Applies one PATCH's fields on top of the current state. A typed PIN is staged only as its hash. */
    fun merge(state: IdentEidState, fields: EidPatchFields): IdentEidState = IdentEidState(
        familyName = fields.familyName?.trim() ?: state.familyName,
        givenNames = fields.givenNames?.trim() ?: state.givenNames,
        birthDate = fields.birthDate ?: state.birthDate,
        streetAddress = fields.streetAddress?.trim() ?: state.streetAddress,
        postalCode = fields.postalCode?.trim() ?: state.postalCode,
        locality = fields.locality?.trim() ?: state.locality,
        restrictedId = fields.restrictedId?.trim() ?: state.restrictedId,
        pinHash = fields.pin?.let { hash(it.trim()) } ?: state.pinHash
    )

    /**
     * The card attests on its own authority (ADR-18), so checking it means format and completeness
     * only; no register is asked. [today] bounds the birth date.
     */
    fun decide(state: IdentEidState, fields: EidPatchFields, today: LocalDate): IdentEidDecision {
        if (cardMissing(state).isNotEmpty()) return IdentEidDecision.Incomplete
        if (fields.touchesCard && !cardWellFormed(state, today)) return IdentEidDecision.CardRejected
        val pinHash = state.pinHash ?: return IdentEidDecision.Incomplete
        val claimed = ClaimedIdentity(
            familyName = state.familyName.orEmpty(),
            givenNames = state.givenNames.orEmpty(),
            birthDate = checkNotNull(state.birthDate),
            streetAddress = state.streetAddress.orEmpty(),
            postalCode = state.postalCode.orEmpty(),
            locality = state.locality.orEmpty()
        )
        return IdentEidDecision.VerifyPin(claimed, checkNotNull(state.restrictedId), pinHash)
    }

    /**
     * Rejected card data is dropped as a whole, PIN included: the next [missingFields] asks for the
     * card again, never for a PIN to a card that did not pass.
     */
    fun rejectCard(): IdentEidState = IdentEidState()

    /** A rejected PIN is dropped; the checked card data stays, so only `pin` is asked for again. */
    fun rejectPin(state: IdentEidState): IdentEidState = state.copy(pinHash = null)

    /** Constant-time, and against the stored hash - the PIN itself is never persisted. */
    fun pinMatchesMock(pinHash: String): Boolean = MessageDigest.isEqual(pinHash.toByteArray(), hash(MOCK_PIN).toByteArray())

    /**
     * Staged: `pin` only appears once the card data is complete and therefore checked. How many
     * screens a client makes of that is its own business (docs/10-frontend.md).
     */
    fun missingFields(state: IdentEidState): List<String> {
        val cardMissing = cardMissing(state)
        if (cardMissing.isNotEmpty()) return cardMissing
        return listOfNotNull("pin".takeIf { state.pinHash.isNullOrBlank() })
    }

    /** Same derivation for start/patch/read - one place turns a state into `next.step`/`stepData`. */
    fun describe(state: IdentEidState): ToolStep = ToolStep("input", MissingFields(missingFields(state)))

    /**
     * A hash of what the card showed, so the audit trail can prove what was seen without keeping it
     * (ADR-39). No document number: it may not be kept (§ 20 PAuswG).
     */
    fun evidenceHash(attested: List<String?>): String = "sha256:" + hash(attested.joinToString("\u001F") { it.orEmpty() })

    /** Everything the card itself shows - the first stage of [missingFields]. */
    private fun cardMissing(state: IdentEidState): List<String> = listOfNotNull(
        "familyName".takeIf { state.familyName.isNullOrBlank() },
        "givenNames".takeIf { state.givenNames.isNullOrBlank() },
        "birthDate".takeIf { state.birthDate == null },
        "streetAddress".takeIf { state.streetAddress.isNullOrBlank() },
        "postalCode".takeIf { state.postalCode.isNullOrBlank() },
        "locality".takeIf { state.locality.isNullOrBlank() },
        "restrictedId".takeIf { state.restrictedId.isNullOrBlank() }
    )

    private fun cardWellFormed(state: IdentEidState, today: LocalDate): Boolean =
        !checkNotNull(state.birthDate).isAfter(today) &&
            POSTAL_CODE.matches(state.postalCode.orEmpty()) &&
            RESTRICTED_ID.matches(state.restrictedId.orEmpty())

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    /** A German postal code, five digits. */
    private val POSTAL_CODE = Regex("\\d{5}")

    /** Stands in for the card's sector-specific identifier; the column holds at most 64 characters. */
    private val RESTRICTED_ID = Regex("[A-Za-z0-9]{16,64}")

    /** Fixed test PIN for the mock, same role as `ident-fsc`'s `VALIDCODE` (docs/08-projektrahmen.md P-5/P-6). */
    const val MOCK_PIN = "123456"
}
