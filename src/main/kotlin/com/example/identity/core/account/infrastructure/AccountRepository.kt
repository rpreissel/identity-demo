package com.example.identity.core.account.infrastructure

import com.example.identity.contract.tool_api.ids.AccountId
import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

/**
 * The key stays a `Long` (see [Account.accountId]); the typed methods are the ones to call, the
 * `Long`-based ones serve them.
 */
@Repository
interface AccountRepository : JpaRepository<Account, Long> {
    fun findAccount(accountId: AccountId): Account? = findById(accountId.value).orElse(null)

    fun existsAccount(accountId: AccountId): Boolean = existsById(accountId.value)

    fun deleteAccount(accountId: AccountId) = deleteById(accountId.value)

    fun findAllIds(): List<AccountId> = findAllIdValues().map(::AccountId)

    @Query("select a.id from Account a")
    fun findAllIdValues(): List<Long>

    /** Every KEK version some account's master key is wrapped with - which KEKs are still needed. */
    @Query("select distinct a.kekVersion from Account a")
    fun kekVersions(): Set<String>

    /** The account's wrapped master key without loading the row - see [StoredMasterKey]. */
    fun findStoredMasterKey(accountId: AccountId): StoredMasterKey? = findStoredMasterKeyById(accountId.value)

    @Query("select new com.example.identity.core.account.infrastructure.StoredMasterKey(a.wrappedMasterKey, a.kekVersion) from Account a where a.id = :id")
    fun findStoredMasterKeyById(@Param("id") id: Long): StoredMasterKey?

    /** Loads the lock root for a change to the account's current state - see [Account.version]. */
    fun findForUpdate(accountId: AccountId): Account? = findForUpdateById(accountId.value)

    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select a from Account a where a.id = :id")
    fun findForUpdateById(@Param("id") id: Long): Account?

    /** Accounts still being set up - no login method yet (ADR-46) - created before [cutoff], oldest first. */
    @Query(
        "select a.id from Account a where a.createdAt < :cutoff " +
            "and not exists (select m.id from AccountAuthMethod m where m.accountId = a.id) order by a.createdAt"
    )
    fun findIdValuesBeingSetUpCreatedBefore(@Param("cutoff") cutoff: Instant, pageable: Pageable): List<Long>

    fun findIdsBeingSetUpCreatedBefore(cutoff: Instant, pageable: Pageable): List<AccountId> =
        findIdValuesBeingSetUpCreatedBefore(cutoff, pageable).map(::AccountId)
}
