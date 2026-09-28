package com.example.identity.core.account

import com.example.identity.core.account.application.PersonLookupKey
import com.example.identity.core.account.infrastructure.ChangeLogRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Whether the change log's search keys are fit for real people (ADR-39). This module only reports;
 * whether a finding stops the start is `ProductionModeCheck`'s decision, which knows the demo mode.
 */
@Component
@Transactional(readOnly = true)
class ChangeLogLookupKeys(
    private val personLookupKey: PersonLookupKey,
    private val repository: ChangeLogRepository,
) {
    /** Key ids in the change log that no configured secret matches: those entries are no longer found by name. */
    fun orphanedKeyIds(): Set<String> = repository.lookupKeyIds() - personLookupKey.knownKeyIds

    /** Whether the secret is the publicly known demo default. */
    fun usesDemoSecret(): Boolean = personLookupKey.usesDemoSecret
}
