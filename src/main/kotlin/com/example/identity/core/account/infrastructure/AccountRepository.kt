package com.example.identity.core.account.infrastructure

import jakarta.persistence.LockModeType
import java.time.Instant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface AccountRepository : JpaRepository<Account, Long> {
    @Query("select a.id from Account a")
    fun findAllIds(): List<Long>

    /** Loads the lock root for a change to the account's current state - see [Account.version]. */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("select a from Account a where a.id = :id")
    fun findForUpdate(@Param("id") id: Long): Account?

    /** Accounts still being set up - no login method yet (ADR-46) - created before [cutoff], oldest first. */
    @Query(
        "select a.id from Account a where a.createdAt < :cutoff " +
            "and not exists (select m.id from AccountAuthMethod m where m.accountId = a.id) order by a.createdAt"
    )
    fun findIdsBeingSetUpCreatedBefore(@Param("cutoff") cutoff: Instant, pageable: Pageable): List<Long>
}
