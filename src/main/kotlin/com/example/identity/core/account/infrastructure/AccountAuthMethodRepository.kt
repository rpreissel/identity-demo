package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.ids.AccountId
import org.springframework.data.repository.Repository
import java.util.UUID

/**
 * Deletes nothing: an instance is deactivated, never removed, so a set-up account never falls back
 * into setup (I-28, docs/invarianten.md). Only the account's own deletion removes its instances,
 * by cascade. A plain [Repository] with the methods in use, so no `delete` exists to call.
 */
@org.springframework.stereotype.Repository
interface AccountAuthMethodRepository : Repository<AccountAuthMethod, UUID> {
    fun save(method: AccountAuthMethod): AccountAuthMethod
    fun <S : AccountAuthMethod> saveAllAndFlush(methods: Iterable<S>): List<S>

    fun findByAccountIdOrderByCreatedAt(accountId: AccountId?): List<AccountAuthMethod>

    /** Whether [accountId] ever had a login method, deactivated ones included (ADR-46). */
    fun existsByAccountId(accountId: AccountId?): Boolean
    fun findByAccountIdAndMethodAndActiveTrueOrderByCreatedAt(accountId: AccountId?, method: String): List<AccountAuthMethod>
    fun findByIdAndAccountId(id: UUID, accountId: AccountId?): AccountAuthMethod?

    fun existsByEnrollmentTypeAndEnrollmentIdAndAccountIdNot(enrollmentType: String, enrollmentId: String, accountId: AccountId?): Boolean
}
