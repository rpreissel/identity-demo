package com.example.identity.tools.ident_kvnr.internal

import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.tools.ident_kvnr.KvnrModule
import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import com.example.identity.contract.tool_api.MissingFields

/**
 * toolId=ident-kvnr. Turns the value an attestation cannot carry, the Versichertennummer or else the
 * Partnernummer (ADR-34), into the register's person reference (ADR-18). It proves nothing on its
 * own and runs only on top of an attested identity ([IdentKvnrDescriptor.requires]).
 */
@Component
class IdentKvnrToolHandler(
    private val sessions: ToolSessionData,
    private val personDirectory: PersonDirectory,
) {

    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        sessions.save(toolSessionId, IdentKvnrToolSession())
        return inProgress()
    }

    /**
     * [personId] is resolved by the controller; a given KVNR wins over [partnerNumber]. An unknown
     * number and one of somebody else's person answer alike, so nobody can probe which numbers
     * exist. The foreign person is still named as `attemptedPersonId`, so the guess counts against
     * the ident rate limit.
     */
    @Transactional
    fun patch(toolSessionId: ToolSessionId, kvnr: String?, partnerNumber: String?, personId: PartnerNumber?, matchesAttestedIdentity: Boolean): ToolOutcome {
        val data = sessions.require<IdentKvnrToolSession>(toolSessionId)
        val byKvnr = !kvnr.isNullOrBlank()
        if (!byKvnr && partnerNumber.isNullOrBlank()) return inProgress()
        sessions.save(toolSessionId, if (byKvnr) data.copy(kvnr = kvnr) else data.copy(partnerNumber = partnerNumber))

        val notAssignable = if (byKvnr) Text("Versichertennummer konnte nicht zugeordnet werden") else Text("Partnernummer konnte nicht zugeordnet werden")
        personId ?: return ToolOutcome.Failed.Identification(notAssignable, attemptedPersonId = null)
        if (!matchesAttestedIdentity) return ToolOutcome.Failed.Identification(notAssignable, attemptedPersonId = personId)

        return ToolOutcome.Completed.Identified(
            claims = listOfNotNull(
                Claim(AttributeType.PERSON_ID, personId.value, ClaimSource.PERSON_DIRECTORY, KvnrModule.maxAcr),
                kvnr?.takeIf { it.isNotBlank() }?.let { Claim(AttributeType.KVNR, it, ClaimSource.PERSON_DIRECTORY, KvnrModule.maxAcr) },
                // Insured with us: the Versicherungsnummer becomes an anchor too (ADR-34).
                personDirectory.memberNumberOf(personId)?.let { Claim(AttributeType.MEMBER_NUMBER, it, ClaimSource.PERSON_DIRECTORY, KvnrModule.maxAcr) }
            ),
            auditDetails = mapOf("methodVersion" to "1.0")
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome.InProgress {
        sessions.require<IdentKvnrToolSession>(toolSessionId)
        return inProgress()
    }

    private fun inProgress() = ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("kvnr")))
}
