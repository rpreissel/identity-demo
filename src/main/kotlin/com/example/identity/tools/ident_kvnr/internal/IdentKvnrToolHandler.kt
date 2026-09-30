package com.example.identity.tools.ident_kvnr.internal

import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.texts.Text
import com.example.identity.tools.ident_kvnr.IdentKvnrDescriptor
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID
import com.example.identity.contract.tool_api.MissingFields

/**
 * toolId=ident-kvnr. Turns the value an attestation cannot carry, the Versichertennummer or else the
 * Partnernummer (ADR-34), into the register's person reference (ADR-18). It proves nothing on its
 * own and runs only on top of an attested identity ([IdentKvnrDescriptor.requires]).
 */
@Component
class IdentKvnrToolHandler(
    private val descriptor: IdentKvnrDescriptor,
    private val repository: IdentKvnrToolSessionRepository,
    private val personDirectory: PersonDirectory,
    private val clock: Clock
) {

    @Transactional
    fun start(toolSessionId: UUID): ToolOutcome {
        repository.save(IdentKvnrToolSession(toolSessionId = toolSessionId, createdAt = clock.instant()))
        return inProgress()
    }

    /**
     * [personId] is resolved by the controller; a given KVNR wins over [partnerNumber]. An unknown
     * number and one of somebody else's person answer alike, so nobody can probe which numbers
     * exist. The foreign person is still named as `attemptedPersonId`, so the guess counts against
     * the ident rate limit.
     */
    @Transactional
    fun patch(toolSessionId: UUID, kvnr: String?, partnerNumber: String?, personId: String?, matchesAttestedIdentity: Boolean): ToolOutcome {
        val data = checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-kvnr tool session: $toolSessionId" }
        val byKvnr = !kvnr.isNullOrBlank()
        if (!byKvnr && partnerNumber.isNullOrBlank()) return inProgress()
        if (byKvnr) data.kvnr = kvnr else data.partnerNumber = partnerNumber
        repository.save(data)

        val notAssignable = if (byKvnr) Text("Versichertennummer konnte nicht zugeordnet werden") else Text("Partnernummer konnte nicht zugeordnet werden")
        personId ?: return ToolOutcome.Failed.Identification(notAssignable, attemptedPersonId = null)
        if (!matchesAttestedIdentity) return ToolOutcome.Failed.Identification(notAssignable, attemptedPersonId = personId)

        return ToolOutcome.Completed.Identified(
            amr = listOf(descriptor.method),
            achievedAcr = descriptor.maxAcr,
            factorTypes = descriptor.factorTypes,
            claims = listOfNotNull(
                Claim(AttributeType.PERSON_ID, personId, ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr),
                kvnr?.takeIf { it.isNotBlank() }?.let { Claim(AttributeType.KVNR, it, ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr) },
                // Insured with us: the Versicherungsnummer becomes an anchor too (ADR-34).
                personDirectory.memberNumberOf(personId)?.let { Claim(AttributeType.MEMBER_NUMBER, it, ClaimSource.PERSON_DIRECTORY, descriptor.maxAcr) }
            ),
            auditDetails = mapOf("methodVersion" to "1.0")
        )
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID): ToolOutcome {
        checkNotNull(repository.findByIdOrNull(toolSessionId)) { "Unknown ident-kvnr tool session: $toolSessionId" }
        return inProgress()
    }

    private fun inProgress() = ToolOutcome.InProgress(nextStep = "input", stepData = MissingFields(listOf("kvnr")))
}
