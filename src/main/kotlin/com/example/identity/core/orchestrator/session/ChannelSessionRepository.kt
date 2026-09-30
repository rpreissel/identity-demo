package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface ChannelSessionRepository : JpaRepository<ChannelSession, UUID> {
    /**
     * [pageable] bounds one retention batch (`RetentionJob`). The caller deletes every returned row
     * before asking again, so page 0 is the remaining backlog.
     */
    fun findByExpiresAtBefore(cutoff: Instant, pageable: Pageable): List<ChannelSession>

    /** Every channel this account was bound to, invalidated on account deletion. */
    fun findByAccountId(accountId: Long): List<ChannelSession>

    fun findByInvitation(invitation: String): List<ChannelSession>

    /** Whether a channel that is neither ended nor expired still works with [accountId] (ADR-46). */
    fun existsByAccountIdAndStateNotInAndExpiresAtAfter(accountId: Long, states: Collection<ChannelState>, now: Instant): Boolean

    /** Channels of [channel] in none of [states] that expire after [now]: the live ones, counted. */
    fun countByChannelAndStateNotInAndExpiresAtAfter(channel: ChannelType, states: Collection<ChannelState>, now: Instant): Long

    /** The same selection across both channel types, ordered and bounded by [pageable]. */
    fun findByStateNotInAndExpiresAtAfter(states: Collection<ChannelState>, now: Instant, pageable: Pageable): List<ChannelSession>

    /** App channels bound to one of these [AuthContext]s. */
    fun findByAuthContextIdIn(authContextIds: Collection<UUID>): List<ChannelSession>

    /** Website channels whose flow ended in one of these Keycloak sessions. */
    fun findByDurableKcSessionIdIn(keycloakSessionIds: Collection<String>): List<ChannelSession>
}
