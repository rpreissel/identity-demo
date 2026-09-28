package com.example.identity.tools.ident_eid.internal

import com.example.identity.contract.tool_api.directory.ClaimedIdentity
import java.security.MessageDigest
import java.time.LocalDate
import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.MissingFields

/**
 * Pure state of the ident-eid flow (docs/03-tool-architektur.md #3), two stages (card -> pin).
 * Only what the simulated card carries: no KVNR and no person reference (ADR-18). The card's
 * restricted identifier is attested as the recognition anchor (ADR-19).
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

/** What [IdentEidFlow.decide] concluded once a state is fully filled in. */
internal sealed interface IdentEidDecision {
    data object Incomplete : IdentEidDecision
    data class Verify(val claimed: ClaimedIdentity, val restrictedId: String, val pinHash: String) : IdentEidDecision
}

internal object IdentEidFlow {

    /** Applies one PATCH's fields on top of the current state. */
    fun merge(state: IdentEidState, fields: EidPatchFields): IdentEidState = IdentEidState(
        familyName = fields.familyName ?: state.familyName,
        givenNames = fields.givenNames ?: state.givenNames,
        birthDate = fields.birthDate ?: state.birthDate,
        streetAddress = fields.streetAddress ?: state.streetAddress,
        postalCode = fields.postalCode ?: state.postalCode,
        locality = fields.locality ?: state.locality,
        restrictedId = fields.restrictedId ?: state.restrictedId,
        pinHash = fields.pin?.let { hash(it.trim()) } ?: state.pinHash
    )

    fun decide(state: IdentEidState): IdentEidDecision {
        if (!hasCardFields(state) || state.pinHash.isNullOrBlank()) return IdentEidDecision.Incomplete
        val claimed = ClaimedIdentity(
            familyName = state.familyName.orEmpty(),
            givenNames = state.givenNames.orEmpty(),
            birthDate = checkNotNull(state.birthDate),
            streetAddress = state.streetAddress.orEmpty(),
            postalCode = state.postalCode.orEmpty(),
            locality = state.locality.orEmpty()
        )
        return IdentEidDecision.Verify(claimed, checkNotNull(state.restrictedId), checkNotNull(state.pinHash))
    }

    /** Constant-time, and against the stored hash - the PIN itself is never persisted. */
    fun pinMatchesMock(pinHash: String): Boolean = MessageDigest.isEqual(pinHash.toByteArray(), hash(MOCK_PIN).toByteArray())

    /** Same derivation for start/patch/read - one place turns a state into `next.step`/`stepData`. */
    fun describe(state: IdentEidState): Pair<String, StepData> = when {
        !hasCardFields(state) -> "card" to MissingFields(CARD_FIELDS)
        else -> "pin" to MissingFields(PIN_FIELDS)
    }

    /**
     * A hash of what the card showed, so the audit trail can prove what was seen without keeping it
     * (ADR-39). No document number: it may not be kept (§ 20 PAuswG).
     */
    fun evidenceHash(attested: List<String?>): String = "sha256:" + hash(attested.joinToString("\u001F") { it.orEmpty() })

    private fun hasCardFields(state: IdentEidState) =
        !state.familyName.isNullOrBlank() && !state.givenNames.isNullOrBlank() && state.birthDate != null &&
            !state.streetAddress.isNullOrBlank() &&
            !state.postalCode.isNullOrBlank() && !state.locality.isNullOrBlank() &&
            !state.restrictedId.isNullOrBlank()

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Everything the card itself shows - read in one go, nothing typed by the user beforehand. */
    val CARD_FIELDS = listOf("familyName", "givenNames", "birthDate", "streetAddress", "postalCode", "locality", "restrictedId")
    val PIN_FIELDS = listOf("pin")

    /** Fixed test PIN for the mock, same role as `ident-fsc`'s `VALIDCODE` (docs/08-projektrahmen.md P-5/P-6). */
    const val MOCK_PIN = "123456"
}
