package com.example.identity.core.orchestrator.journey

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import com.example.identity.core.orchestrator.domain.journey.JourneyLifecycle
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthJourneyRepository : JpaRepository<AuthJourney, UUID> {
    fun findByJourneyId(journeyId: JourneyId): AuthJourney?

    /**
     * At most one running journey per channel (docs/07-betrieb.md #2). A parent waiting on a
     * sub-journey is SUSPENDED, not STARTED, so this stays single-valued.
     */
    fun findFirstByChannelSessionIdAndLifecycleOrderByCreatedAtDesc(
        channelSessionId: ChannelSessionId,
        lifecycle: JourneyLifecycle
    ): AuthJourney?

    /**
     * Retention clock starts at consumedAt or expiresAt (docs/07-betrieb.md #3). [pageable] bounds
     * one batch; callers delete every returned id before asking again, so page 0 is the backlog.
     */
    @Query(
        """
        select j.journeyId from AuthJourney j
        where j.consumedAt < :cutoff or j.expiresAt < :cutoff
        """
    )
    fun findIdValuesForRetention(cutoff: Instant, pageable: Pageable): List<UUID>

    /** Bare UUIDs in and out, as for every id collection: Hibernate would see the boxed id otherwise. */
    fun findIdsForRetention(cutoff: Instant, pageable: Pageable): List<JourneyId> =
        findIdValuesForRetention(cutoff, pageable).map(::JourneyId)

    fun deleteByConsumedAtBeforeOrExpiresAtBefore(consumedCutoff: Instant, expiresCutoff: Instant): Long

    fun deleteAllByJourneyIdInBatch(journeyIds: Collection<JourneyId>) = deleteAllByIdInBatch(journeyIds.map { it.value })

    fun findIdsByChannelSessionIdIn(channelSessionIds: Collection<ChannelSessionId>): List<JourneyId> =
        findIdValuesByChannelSessionIdIn(channelSessionIds.map { it.value }).map(::JourneyId)

    @Query("select j.journeyId from AuthJourney j where j.channelSessionId in :channelSessionIds")
    fun findIdValuesByChannelSessionIdIn(@Param("channelSessionIds") channelSessionIds: Collection<UUID>): List<UUID>
}
