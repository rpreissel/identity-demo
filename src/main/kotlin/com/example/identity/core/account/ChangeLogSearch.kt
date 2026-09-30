package com.example.identity.core.account

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.infrastructure.ChangeLogRepository
import com.example.identity.core.account.application.PersonLookupKey
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/** One line of the change log, as the search hands it out. */
data class ChangeLogRecord(
    val accountId: AccountId,
    val changeType: String,
    val subject: String?,
    val acr: String?,
    val details: Map<String, Any?>,
    val occurredAt: Instant,
)

/**
 * Finds a person in the change log (ADR-39) by name, first name and date of birth, above all after
 * their account was deleted. Returns the trail of every account ever identified as that person.
 * Several can match; the provider reference in each `IDENTIFIED` entry tells them apart.
 */
@Service
class ChangeLogSearch(
    private val repository: ChangeLogRepository,
    private val personLookupKey: PersonLookupKey,
) {
    @Transactional(readOnly = true)
    fun byPerson(name: String, vorname: String, geburtsdatum: LocalDate): List<ChangeLogRecord> =
        personLookupKey.candidates(name, vorname, geburtsdatum)
            .takeIf { it.isNotEmpty() }
            ?.let { trailOf(repository.accountsWithLookupKeyIn(it)) }
            .orEmpty()

    /** The same for a register person id, when the person is known to the register. */
    @Transactional(readOnly = true)
    fun byPersonId(personId: PartnerNumber): List<ChangeLogRecord> =
        trailOf(repository.accountsWithPersonId(personId))

    private fun trailOf(accountIds: List<AccountId>): List<ChangeLogRecord> =
        if (accountIds.isEmpty()) emptyList()
        else repository.findByAccountIdInOrderByAccountIdAscOccurredAtAsc(accountIds).map {
            ChangeLogRecord(checkNotNull(it.accountId), it.changeType.name, it.subject, it.acr, it.details.orEmpty(), it.occurredAt)
        }
}
