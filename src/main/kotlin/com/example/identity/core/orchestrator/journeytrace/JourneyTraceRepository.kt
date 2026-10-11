package com.example.identity.core.orchestrator.journeytrace

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface JourneyTraceRepository : JpaRepository<JourneyTraceEntry, UUID> {
    /**
     * One page of journey starts (one `Started` entry per journey), newest first, strictly before
     * the cursor ([beforeAt], [beforeId]): keyset paging, so a page costs the same however deep.
     */
    @Query(
        """
        select e from JourneyTraceEntry e
        where e.eventType = 'Started' and e.createdAt >= :from
          and (e.createdAt < :beforeAt or (e.createdAt = :beforeAt and e.logId < :beforeId))
        order by e.createdAt desc, e.logId desc
        """
    )
    fun findStartsBefore(
        @Param("from") from: Instant,
        @Param("beforeAt") beforeAt: Instant,
        @Param("beforeId") beforeId: UUID,
        pageable: Pageable
    ): List<JourneyTraceEntry>

    /** As [findStartsBefore], narrowed to some channels. Bare UUIDs, as for every id collection. */
    @Query(
        """
        select e from JourneyTraceEntry e
        where e.eventType = 'Started' and e.createdAt >= :from and e.channelSessionId in :channelSessionIds
          and (e.createdAt < :beforeAt or (e.createdAt = :beforeAt and e.logId < :beforeId))
        order by e.createdAt desc, e.logId desc
        """
    )
    fun findStartsInChannelsBefore(
        @Param("channelSessionIds") channelSessionIds: Collection<UUID>,
        @Param("from") from: Instant,
        @Param("beforeAt") beforeAt: Instant,
        @Param("beforeId") beforeId: UUID,
        pageable: Pageable
    ): List<JourneyTraceEntry>

    /**
     * The channels an account used since [from]. Also the entries before the account was bound
     * belong to them. Nullable only so Kotlin passes the boxed [AccountId] its converter expects,
     * not the bare Long.
     */
    @Query("select distinct e.channelSessionId from JourneyTraceEntry e where e.accountId = :accountId and e.createdAt >= :from")
    fun findChannelSessionIdValuesOfBoxedAccount(@Param("accountId") accountId: AccountId?, @Param("from") from: Instant): List<UUID>

    fun findChannelSessionIdValuesOfAccount(accountId: AccountId, from: Instant): List<UUID> =
        findChannelSessionIdValuesOfBoxedAccount(accountId, from)

    /** Every step of the given journeys, oldest first. */
    @Query("select e from JourneyTraceEntry e where e.journeyId in :journeyIds order by e.createdAt, e.logId")
    fun findByJourneyIdValueIn(@Param("journeyIds") journeyIds: Collection<UUID>): List<JourneyTraceEntry>

    /** The account each channel resolved, for entries logged before it was bound. */
    @Query(
        """
        select distinct e.channelSessionId, e.accountId from JourneyTraceEntry e
        where e.channelSessionId in :channelSessionIds and e.accountId is not null
        """
    )
    fun findAccountsOfChannelValues(@Param("channelSessionIds") channelSessionIds: Collection<UUID>): List<Array<Any>>

    /** One channel's entries, oldest first, at most [pageable]'s size. */
    @Query("select e from JourneyTraceEntry e where e.channelSessionId = :channelSessionId order by e.createdAt, e.logId")
    fun findByChannelSessionIdOldestFirst(@Param("channelSessionId") channelSessionId: ChannelSessionId, pageable: Pageable): List<JourneyTraceEntry>

    /** Retention sweep. A bulk statement, because this is the highest-volume table. */
    @Modifying
    @Query("delete from JourneyTraceEntry e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int

    /**
     * Erasure path. Also keyed by channel, because entries written before the channel resolved an
     * account have no `accountId`. One statement with flush and clear: pending changes are written
     * first, and the deleted entries are detached. Otherwise their mutable `detail` map is flushed
     * as an UPDATE against deleted rows, which surfaces as a spurious 409.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from JourneyTraceEntry e where e.accountId = :accountId or e.channelSessionId in :channelSessionIds")
    fun deleteByAccountIdOrChannelSessionIdValueIn(
        @Param("accountId") accountId: AccountId?,
        @Param("channelSessionIds") channelSessionIds: Collection<UUID>
    ): Int

    /** The channel ids travel as bare UUIDs: Hibernate sees the boxed id in a collection otherwise. */
    fun deleteByAccountIdOrChannelSessionIdIn(accountId: AccountId, channelSessionIds: Collection<ChannelSessionId>): Int =
        deleteByAccountIdOrChannelSessionIdValueIn(accountId, channelSessionIds.map { it.value })
}
