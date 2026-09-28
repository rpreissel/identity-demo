package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.claims.AttributeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/** The resolve-identity index of the claims model, see [AccountAnchor]. Written only via `AccountService`. */
@Repository
interface AccountAnchorRepository : JpaRepository<AccountAnchor, Long> {
    fun findByAttributeTypeAndValue(attributeType: AttributeType, value: String): AccountAnchor?
    fun findByAccountIdAndAttributeType(accountId: Long, attributeType: AttributeType): AccountAnchor?
    fun findByAccountId(accountId: Long): List<AccountAnchor>
}
