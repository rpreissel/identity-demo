package com.example.identity.core.orchestrator.journeytrace

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface JourneyTraceRepository : JpaRepository<JourneyTraceEntry, UUID> {
    /** The operator's view across every channel and account (`AdminJourneyTraceController`), newest first. */
    fun findAllByOrderByCreatedAtDesc(pageable: Pageable): List<JourneyTraceEntry>

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
    fun deleteByAccountIdOrChannelSessionIdIn(
        @Param("accountId") accountId: Long,
        @Param("channelSessionIds") channelSessionIds: Collection<UUID>
    ): Int
}
