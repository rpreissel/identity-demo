package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ids.AccountId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface DeviceAccountLinkRepository : JpaRepository<DeviceAccountLink, String> {
    fun deleteByAccountId(accountId: AccountId?)
}
