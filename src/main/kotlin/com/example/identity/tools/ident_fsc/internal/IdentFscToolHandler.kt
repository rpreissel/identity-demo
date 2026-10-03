package com.example.identity.tools.ident_fsc.internal

import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.tools.ident_fsc.FscModule
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.directory.ActivationCodes
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.claims.ClaimSource
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

/**
 * toolId=ident-fsc (docs/06-ablaeufe.md #2). Resolves KVNR (or Partnernummer, ADR-34), name, date of
 * birth and FSC into a person. [patch]'s [personId] arrives resolved by the controller over
 * [PersonDirectory]. The register issued the code, so it checks it too ([ActivationCodes], ADR-31).
 */
private val PERSONAL_DETAILS_REJECTED = Text("Die Angaben passen zu keiner Person, die wir kennen")

@Component
class IdentFscToolHandler(
    private val sessions: ToolSessionData,
    private val activationCodes: ActivationCodes,
    private val personDirectory: PersonDirectory,
) {

    /** Called directly by IdentFscToolController; nothing needs resolving before this can start. */
    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.save(toolSessionId, IdentFscToolSession())
        return outcomeFor(IdentFscState())
    }

    /**
     * [rate-limited] guards the guessable code and answers like "code invalid"; a distinct lock
     * response would reveal which KVNRs exist. A rejected personal-data check also charges the
     * person's counter, so probing runs into the same lock.
     */
    @Transactional
    fun patch(
        toolSessionId: ToolSessionId,
        kvnr: String?,
        partnerNumber: String?,
        familyName: String?,
        givenNames: String?,
        birthDate: LocalDate?,
        fsc: String?,
        personId: PartnerNumber?,
        rateLimited: Boolean
    ): ToolOutcome {
        val data = sessions.require<IdentFscToolSession>(toolSessionId)

        val input = IdentFscInput(kvnr, partnerNumber, familyName, givenNames, birthDate, fsc, personId)
        val merged = IdentFscFlow.merge(data.toState(), input, activationCodes::digest)

        // Every personal-data rejection answers alike, whether the KVNR is unknown or a
        // name/birthdate is off - anything finer would tell a caller which part was wrong.
        val outcome = when (val decision = IdentFscFlow.decide(merged, input)) {
            IdentFscDecision.Incomplete -> merged to outcomeFor(merged)

            IdentFscDecision.PersonNotFound ->
                IdentFscFlow.rejectPersonalDetails() to ToolOutcome.Failed.Identification(PERSONAL_DETAILS_REJECTED, attemptedPersonId = null)

            is IdentFscDecision.VerifyPersonalDetails -> {
                // Name and birthdate are checked, not merely collected, before the code is asked
                // for, so nobody types a code for data that could never match.
                val matches = personDirectory.matchesPersonalDetails(
                    decision.personId, decision.familyName, decision.givenNames, decision.birthDate
                )
                when {
                    !matches -> IdentFscFlow.rejectPersonalDetails() to
                        ToolOutcome.Failed.Identification(PERSONAL_DETAILS_REJECTED, attemptedPersonId = decision.personId)
                    // All five in one PATCH: the personal data holds, so the code is next.
                    merged.fscHash != null -> verifyCode(toolSessionId, merged, decision.personId, merged.fscHash, rateLimited)
                    else -> merged to outcomeFor(merged)
                }
            }

            is IdentFscDecision.VerifyCode -> verifyCode(toolSessionId, merged, decision.personId, decision.fscHash, rateLimited)
        }

        sessions.save(toolSessionId, outcome.first.toSession())
        return outcome.second
    }

    private fun verifyCode(
        toolSessionId: ToolSessionId,
        state: IdentFscState,
        personId: PartnerNumber,
        fscHash: String,
        rateLimited: Boolean
    ): Pair<IdentFscState, ToolOutcome> {
        if (rateLimited || !activationCodes.isValid(personId, fscHash)) {
            return IdentFscFlow.rejectCode(state) to
                ToolOutcome.Failed.Identification(Text("Freischaltcode ungueltig oder abgelaufen"), attemptedPersonId = personId)
        }
        return state to ToolOutcome.Completed.Identified(
            claims = listOfNotNull(
                // FSC is a master-data channel: every attribute this run asserts
                // was checked against personenverzeichnis, hence PERSON_DIRECTORY as the
                // trust anchor, not this tool's own id.
                Claim(AttributeType.PERSON_ID, personId.value, ClaimSource.PERSON_DIRECTORY, FscModule.maxAcr),
                // A Partner identifies by Partnernummer and has no KVNR (ADR-34).
                state.kvnr?.let { Claim(AttributeType.KVNR, it, ClaimSource.PERSON_DIRECTORY, FscModule.maxAcr) },
                Claim(AttributeType.FAMILY_NAME, checkNotNull(state.familyName), ClaimSource.PERSON_DIRECTORY, FscModule.maxAcr),
                Claim(AttributeType.GIVEN_NAMES, checkNotNull(state.givenNames), ClaimSource.PERSON_DIRECTORY, FscModule.maxAcr),
                // Checked against the register like the name (matchesPersonalDetails) - and one of the
                // three things that find this identification in the change log (ADR-39).
                Claim(AttributeType.BIRTH_DATE, checkNotNull(state.birthDate).toString(), ClaimSource.PERSON_DIRECTORY, FscModule.maxAcr),
                // Insured with us: the Versicherungsnummer becomes an anchor too (ADR-34).
                personDirectory.memberNumberOf(personId)?.let { Claim(AttributeType.MEMBER_NUMBER, it, ClaimSource.PERSON_DIRECTORY, FscModule.maxAcr) }
            ),
            auditDetails = mapOf(
                "provider" to "fsc-service",
                "providerTxId" to "FSC-$toolSessionId",
                "methodVersion" to "1.0",
                "evidenceHash" to IdentFscFlow.evidenceHash(state.kvnr ?: state.partnerNumber.orEmpty(), fscHash)
            )
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        return outcomeFor(sessions.require<IdentFscToolSession>(toolSessionId).toState())
    }

    private fun outcomeFor(state: IdentFscState): ToolOutcome.InProgress {
        val (step, fields) = IdentFscFlow.describe(state)
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    private fun IdentFscToolSession.toState(): IdentFscState = IdentFscState(kvnr, partnerNumber, familyName, givenNames, birthDate, fscHash, personId)

    private fun IdentFscState.toSession(): IdentFscToolSession = IdentFscToolSession(
        kvnr = kvnr,
        partnerNumber = partnerNumber,
        personId = personId,
        familyName = familyName,
        givenNames = givenNames,
        birthDate = birthDate,
        fscHash = fscHash,
    )
}
