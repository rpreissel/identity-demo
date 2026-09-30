package com.example.identity.tools.ident_fsc.internal

import com.example.identity.contract.tool_api.values.PartnerNumber
import java.security.MessageDigest
import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.MissingFields
import java.time.LocalDate

/**
 * Pure state of the ident-fsc flow (docs/03-tool-architektur.md #3). One flat shape: five
 * independently suppliable fields have no named positions, so [missingFields] derives what is needed.
 */
internal data class IdentFscState(
    val kvnr: String? = null,
    /** Only without a KVNR (a Partner, ADR-34) - at most one of the two is set, see [IdentFscFlow.merge]. */
    val partnerNumber: String? = null,
    val familyName: String? = null,
    val givenNames: String? = null,
    val birthDate: LocalDate? = null,
    val fscHash: String? = null,
    val personId: PartnerNumber? = null
)

/** What one PATCH submitted - all optional, exactly the API's "only the changed part" rule. */
internal data class IdentFscInput(
    val kvnr: String? = null,
    val partnerNumber: String? = null,
    val familyName: String? = null,
    val givenNames: String? = null,
    val birthDate: LocalDate? = null,
    val fsc: String? = null,
    val personId: PartnerNumber? = null
) {
    /** Whether this PATCH touched the personal data - which is then checked again, right away. */
    val touchesPersonalDetails: Boolean get() = kvnr != null || partnerNumber != null || familyName != null || givenNames != null || birthDate != null
}

/**
 * What [IdentFscFlow.decide] concluded from a merged state. The personal data is checked the
 * moment it is complete, before `fsc` is even asked for - so the stored personal data is only
 * ever incomplete or already verified, and no flag has to say which.
 */
internal sealed interface IdentFscDecision {
    data object Incomplete : IdentFscDecision

    /** The personal data is complete but its KVNR or Partnernummer resolved no person. */
    data object PersonNotFound : IdentFscDecision

    /** The personal data was just (re-)supplied: check it against the register first. */
    data class VerifyPersonalDetails(
        val personId: PartnerNumber,
        val familyName: String,
        val givenNames: String,
        val birthDate: LocalDate
    ) : IdentFscDecision

    /** The personal data stands verified and a code is present: check the code. */
    data class VerifyCode(val personId: PartnerNumber, val fscHash: String) : IdentFscDecision
}

internal object IdentFscFlow {

    /**
     * Applies one PATCH's fields on top of the current state; blank clears. The person travels with
     * the identifier it was resolved from, so a new identifier that resolves no one leaves no stale
     * person. KVNR and Partnernummer exclude each other, the KVNR wins (ADR-34). A typed code is
     * staged only as its [digest].
     */
    fun merge(state: IdentFscState, input: IdentFscInput, digest: (String) -> String): IdentFscState {
        val kvnr = input.kvnr?.ifBlank { null }
        val partnerNumber = input.partnerNumber?.ifBlank { null }
        val (mergedKvnr, mergedPartnerNumber) = when {
            kvnr != null -> kvnr to null
            partnerNumber != null -> null to partnerNumber
            else -> (if (input.kvnr != null) null else state.kvnr) to (if (input.partnerNumber != null) null else state.partnerNumber)
        }
        return IdentFscState(
            kvnr = mergedKvnr,
            partnerNumber = mergedPartnerNumber,
            familyName = input.familyName ?: state.familyName,
            givenNames = input.givenNames ?: state.givenNames,
            birthDate = input.birthDate ?: state.birthDate,
            fscHash = input.fsc?.let { digest(it.trim()) } ?: state.fscHash,
            personId = if (input.kvnr != null || input.partnerNumber != null) input.personId else state.personId
        )
    }

    fun decide(state: IdentFscState, input: IdentFscInput): IdentFscDecision {
        if (personalienMissing(state).isNotEmpty()) return IdentFscDecision.Incomplete
        val personId = state.personId ?: return IdentFscDecision.PersonNotFound
        if (input.touchesPersonalDetails) {
            return IdentFscDecision.VerifyPersonalDetails(
                personId, state.familyName.orEmpty(), state.givenNames.orEmpty(), checkNotNull(state.birthDate)
            )
        }
        val fscHash = state.fscHash ?: return IdentFscDecision.Incomplete
        return IdentFscDecision.VerifyCode(personId, fscHash)
    }

    /**
     * Rejected personal data is dropped as a whole, code included: the next [missingFields] asks
     * for the personal data again, never for a code that would belong to an unverified person.
     */
    fun rejectPersonalDetails(): IdentFscState = IdentFscState()

    /** A rejected code is dropped; the verified personal data stays, so only `fsc` is asked for again. */
    fun rejectCode(state: IdentFscState): IdentFscState = state.copy(fscHash = null)

    /**
     * Staged: `fsc` only appears once the personal data is complete and therefore verified. How many
     * screens a client makes of that is its own business (docs/10-frontend.md).
     */
    fun missingFields(state: IdentFscState): List<String> {
        val lookupMissing = personalienMissing(state)
        if (lookupMissing.isNotEmpty()) return lookupMissing
        return listOfNotNull("fsc".takeIf { state.fscHash.isNullOrBlank() })
    }

    private fun personalienMissing(state: IdentFscState): List<String> = listOfNotNull(
        // Either identifier will do; the client asks for the KVNR first (ADR-34).
        "kvnr".takeIf { state.kvnr.isNullOrBlank() && state.partnerNumber.isNullOrBlank() },
        "familyName".takeIf { state.familyName.isNullOrBlank() },
        "givenNames".takeIf { state.givenNames.isNullOrBlank() },
        "birthDate".takeIf { state.birthDate == null }
    )

    /** Same derivation for start/patch/read - one place turns a state into `next.step`/`stepData`. */
    fun describe(state: IdentFscState): Pair<String, StepData> = "input" to MissingFields(missingFields(state))

    /** [identifier] is the KVNR, or the Partnernummer for a Partner. */
    fun evidenceHash(identifier: String, fscHash: String): String = "sha256:" + hash("$identifier:$fscHash")

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
