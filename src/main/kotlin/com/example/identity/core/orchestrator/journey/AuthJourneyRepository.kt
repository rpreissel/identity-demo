package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.journey.JourneyLifecycle
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthJourneyRepository : JpaRepository<AuthJourney, UUID> {
    /**
     * At most one running journey per channel (docs/07-betrieb.md #2). A parent waiting on a
     * sub-journey is SUSPENDED, not STARTED, so this stays single-valued.
     */
    fun findFirstByChannelSessionIdAndLifecycleOrderByCreatedAtDesc(
        channelSessionId: UUID,
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
    fun findIdsForRetention(cutoff: Instant, pageable: Pageable): List<UUID>

    fun deleteByConsumedAtBeforeOrExpiresAtBefore(consumedCutoff: Instant, expiresCutoff: Instant): Long

    /**
     * Clears journeys of channels about to be deleted, even if not yet aged out; otherwise
     * FK_AUTH_JOURNEY_CHANNEL_SESSION rejects the channel delete.
     */
    fun deleteByChannelSessionIdIn(channelSessionIds: Collection<UUID>): Long

    @Query("select j.journeyId from AuthJourney j where j.channelSessionId in :channelSessionIds")
    fun findIdsByChannelSessionIdIn(channelSessionIds: Collection<UUID>): List<UUID>
}
