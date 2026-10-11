package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.ids.AccountId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface AccountRetractionRepository : JpaRepository<AccountRetraction, Long> {
    /** Every withdrawal of one account - for the operator's account view. */
    fun findByAccountIdOrderByRetractedAt(accountId: AccountId?): List<AccountRetraction>
}
