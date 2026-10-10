package com.example.identity.core.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * Offers no read-modify-write path: every mutation is a single statement, because a load-then-save
 * is what an attacker parallelizes (see [RateLimitCounter]). Creating a missing row needs its own
 * transaction ([RateLimitRecordInitializer]).
 */
@Repository
interface RateLimitRecordRepository : JpaRepository<RateLimitRecord, RateLimitRecordId> {

    /**
     * A new counter at zero, an INSERT only. Not `save`: with an assigned id Spring Data merges,
     * and a merge onto a row a concurrent request just created would reset its count.
     */
    @Modifying
    @Query(
        value = "INSERT INTO orchestrator.rate_limit (scope, subject, failed_count, updated_at) VALUES (:scope, :subject, 0, :now)",
        nativeQuery = true,
    )
    fun insertAtZero(@Param("scope") scope: String, @Param("subject") subject: String, @Param("now") now: Instant)

    /**
     * One atomic increment plus the lockout decision from the same post-increment value. The row
     * lock holds until commit, so [RateLimitCounter]'s follow-up read sees a stable value.
     *
     * A lock that has run out starts the count over: otherwise one wrong guess every lockout period
     * would keep a stranger's account locked for good.
     *
     * @return 0 when no counter row exists yet: the caller creates one and retries.
     */
    @Modifying
    @Query(
        """
            update RateLimitRecord t
               set t.failedCount = case when t.lockedUntil <= :now then 1 else t.failedCount + 1 end,
                   t.lockedUntil = case
                       when (case when t.lockedUntil <= :now then 1 else t.failedCount + 1 end) >= :maxFailures then :lockUntil
                       when t.lockedUntil <= :now then null
                       else t.lockedUntil end,
                   t.updatedAt = :now
             where t.id.scope = :scope and t.id.subject = :subject
        """
    )
    fun incrementFailure(
        @Param("scope") scope: String,
        @Param("subject") subject: String,
        @Param("maxFailures") maxFailures: Int,
        @Param("lockUntil") lockUntil: Instant,
        @Param("now") now: Instant
    ): Int

    /**
     * Rolling-window counterpart of [incrementFailure]. The window restart happens in the same
     * statement, so two requests at the boundary cannot both reset the counter to 1.
     */
    @Modifying
    @Query(
        """
            update RateLimitRecord t
               set t.failedCount = case when t.updatedAt < :windowStart then 1 else t.failedCount + 1 end,
                   t.updatedAt = :now
             where t.id.scope = :scope and t.id.subject = :subject
        """
    )
    fun incrementWithinWindow(
        @Param("scope") scope: String,
        @Param("subject") subject: String,
        @Param("windowStart") windowStart: Instant,
        @Param("now") now: Instant
    ): Int

    /**
     * [incrementFailure] for an attempt not yet made: counts it only while the subject is not
     * locked, in the same statement. So the check and the count cannot be split by a parallel
     * attempt (docs/07-betrieb.md #4).
     *
     * @return 0 when no counter row exists yet, or the subject is locked.
     */
    @Modifying
    @Query(
        """
            update RateLimitRecord t
               set t.failedCount = case when t.lockedUntil <= :now then 1 else t.failedCount + 1 end,
                   t.lockedUntil = case
                       when (case when t.lockedUntil <= :now then 1 else t.failedCount + 1 end) >= :maxFailures then :lockUntil
                       when t.lockedUntil <= :now then null
                       else t.lockedUntil end,
                   t.updatedAt = :now
             where t.id.scope = :scope and t.id.subject = :subject
               and (t.lockedUntil is null or t.lockedUntil <= :now)
        """
    )
    fun bookAttempt(
        @Param("scope") scope: String,
        @Param("subject") subject: String,
        @Param("maxFailures") maxFailures: Int,
        @Param("lockUntil") lockUntil: Instant,
        @Param("now") now: Instant
    ): Int

    /**
     * Takes back one attempt [bookAttempt] counted but that guessed nothing. A lock that booking
     * tripped goes with it once the count is below [maxFailures] again.
     */
    @Modifying
    @Query(
        """
            update RateLimitRecord t
               set t.failedCount = case when t.failedCount > 0 then t.failedCount - 1 else 0 end,
                   t.lockedUntil = case when t.failedCount - 1 < :maxFailures then null else t.lockedUntil end,
                   t.updatedAt = :now
             where t.id.scope = :scope and t.id.subject = :subject and t.failedCount > 0
        """
    )
    fun refundAttempt(
        @Param("scope") scope: String,
        @Param("subject") subject: String,
        @Param("maxFailures") maxFailures: Int,
        @Param("now") now: Instant
    ): Int

    @Modifying
    @Query(
        """
            update RateLimitRecord t
               set t.failedCount = 0,
                   t.lockedUntil = null,
                   t.updatedAt = :now
             where t.id.scope = :scope and t.id.subject = :subject
               and (t.failedCount <> 0 or t.lockedUntil is not null)
        """
    )
    fun resetCounter(
        @Param("scope") scope: String,
        @Param("subject") subject: String,
        @Param("now") now: Instant
    ): Int

    @Query("select t.failedCount from RateLimitRecord t where t.id.scope = :scope and t.id.subject = :subject")
    fun findFailedCount(@Param("scope") scope: String, @Param("subject") subject: String): Int?

    @Query("select t.lockedUntil from RateLimitRecord t where t.id.scope = :scope and t.id.subject = :subject")
    fun findLockedUntil(@Param("scope") scope: String, @Param("subject") subject: String): Instant?

    /**
     * Retention sweep (`RetentionJob`). Old rows carry no value, but an attacker can mint unbounded
     * [RateLimitScope.BINDING_KEY] keys and keys of the modules' send budgets. An active lock is never
     * swept, because that would hand the attacker a reset.
     */
    @Modifying
    @Query(
        """
            delete from RateLimitRecord t
             where t.updatedAt < :cutoff
               and (t.lockedUntil is null or t.lockedUntil < :now)
        """
    )
    fun deleteStaleCounters(@Param("cutoff") cutoff: Instant, @Param("now") now: Instant): Int

    /**
     * Erasure path (`AccountDeletionService`), account-keyed scopes only. The other subjects belong
     * to another entity or are not account-derived. Clearing them would turn account deletion into
     * a rate limit reset.
     */
    @Modifying
    @Query("delete from RateLimitRecord t where t.id.subject = :subject and t.id.scope in :scopes")
    fun deleteBySubjectAndScopeIn(
        @Param("subject") subject: String,
        @Param("scopes") scopes: Collection<String>
    ): Int
}
