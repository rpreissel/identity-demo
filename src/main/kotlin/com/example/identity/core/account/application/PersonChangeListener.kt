package com.example.identity.core.account.application

import com.example.identity.core.account.infrastructure.PERSON_CHANGE_EXECUTOR
import org.springframework.scheduling.annotation.Async
import com.example.identity.core.account.AccountService
import com.example.identity.contract.tool_api.directory.PersonChanged
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.stereotype.Component

/**
 * The account side of a change in the Personenverzeichnis (ADR-34): the bound account follows it.
 * The change stays in the Event Publication Registry until this ran through, so a failure is
 * retried, never lost (ADR-29).
 */
@Component
class PersonChangeListener(private val accountService: AccountService) {

    /**
     * On one lane ([PERSON_CHANGE_EXECUTOR]), not Spring's shared async pool: two changes to the
     * same person (a Versicherungsnummer changed twice) must be applied in the order the directory
     * published them - the Event Publication Registry guarantees delivery, not order.
     */
    @ApplicationModuleListener
    @Async(PERSON_CHANGE_EXECUTOR)
    fun onPersonChanged(event: PersonChanged) {
        accountService.applyDirectoryChange(event)
    }
}
