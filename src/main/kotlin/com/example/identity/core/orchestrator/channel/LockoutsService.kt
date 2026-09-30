package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.orchestrator.session.PersonLockoutService
import com.example.identity.core.orchestrator.session.AccountLockoutService
import com.example.identity.contract.tool_api.Lockouts
import org.springframework.stereotype.Service

/** Implements the [Lockouts] port on the orchestrator's rate limits. */
@Service
class LockoutsService(
    private val accountLockoutService: AccountLockoutService,
    private val personLockoutService: PersonLockoutService
) : Lockouts {
    override fun isLockedOut(accountId: AccountId?): Boolean =
        accountId?.let { accountLockoutService.isLocked(it) } ?: false

    override fun isIdentLockedOut(personId: PartnerNumber?): Boolean =
        personId?.let { personLockoutService.isLocked(it) } ?: false
}
