package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.AuthIntent
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID
import com.example.identity.core.orchestrator.domain.AcrLevels

@Service
@Transactional
class SessionManagementService(
    private val channelSessionRepository: ChannelSessionRepository,
    private val toolSessionRepository: ToolSessionRepository,
    private val deviceAccountLinkRepository: DeviceAccountLinkRepository
) {

    // ChannelSession management ------------------------------------------------

    /**
     * Always creates a new ChannelSession, never looks one up by [bindingKeyRef]: DPoP proves the
     * device, not which session to resume. [accountId] pre-links the channel to a known device.
     */
    fun createChannelSession(
        bindingKeyRef: String,
        channel: ChannelType,
        ttl: Duration,
        accountId: Long?
    ): ChannelSession {
        val session = ChannelSession(channel, bindingKeyRef, Instant.now().plus(ttl))
        session.accountId = accountId
        return channelSessionRepository.save(session)
    }

    /**
     * KEYCLOAK channel creation for an upsert (docs/05-api.md Abschnitt 3). The id is chosen by
     * Keycloak; the caller has checked it is unused. `entryIntent` is [AuthIntent.KC_SELECT_METHOD]
     * or [AuthIntent.REGISTER]; the other entry intents assume an App channel. [availableTools] is
     * the extension's declaration of what it can render, as on the App channel.
     */
    fun createKcChannelSession(
        channelSessionId: UUID,
        channelAnchor: String,
        accountId: Long?,
        ttl: Duration,
        availableTools: Set<String>,
        entryIntent: AuthIntent = AuthIntent.KC_SELECT_METHOD
    ): ChannelSession {
        val session = ChannelSession(ChannelType.KEYCLOAK, null, Instant.now().plus(ttl))
        session.channelSessionId = channelSessionId
        session.channelAnchor = channelAnchor
        session.accountId = accountId
        session.entryIntent = entryIntent
        session.availableClientTools = availableTools.toMutableSet()
        return channelSessionRepository.save(session)
    }

    fun findChannelSessionById(channelSessionId: UUID): ChannelSession? =
        channelSessionRepository.findByIdOrNull(channelSessionId)
            ?.takeIf { !it.isExpired }

    /** The channel this request works on, read again after a write. Gone only if it expired meanwhile. */
    fun reloadChannelSession(channelSessionId: UUID): ChannelSession =
        checkNotNull(findChannelSessionById(channelSessionId)) { "Channel $channelSessionId vanished mid-request" }

    fun updateChannelSession(session: ChannelSession): ChannelSession {
        session.touch()
        return channelSessionRepository.save(session)
    }

    fun updateChannelState(channelSessionId: UUID, newState: ChannelState) {
        channelSessionRepository.findByIdOrNull(channelSessionId)?.let { session ->
            session.state = newState
            session.touch()
            channelSessionRepository.save(session)
        }
    }

    /**
     * Only raises, never lowers (docs/05-api.md, step-ups). Compared against the effective floor,
     * so an explicit "loa1" cannot undercut the implicit loa2 baseline.
     */
    fun raiseChannelAcrFloor(channelSessionId: UUID, requiredAcr: String) {
        channelSessionRepository.findByIdOrNull(channelSessionId)?.let { session ->
            val effectiveFloor = session.acrFloor ?: AcrLevels.DEFAULT_REQUIRED_ACR.value
            session.acrFloor = AcrLevels.max(effectiveFloor, requiredAcr)
            session.touch()
            channelSessionRepository.save(session)
        }
    }

    fun bindAccountAndAuthContext(channelSessionId: UUID, accountId: Long, authContextId: UUID) {
        channelSessionRepository.findByIdOrNull(channelSessionId)?.let { session ->
            session.accountId = accountId
            session.authContextId = authContextId
            session.touch()
            channelSessionRepository.save(session)
        }
    }

    // Device-account link management --------------------------------------------

    /** Known account for this device, if any. */
    fun findLinkedAccountId(bindingKeyRef: String): Long? =
        deviceAccountLinkRepository.findByIdOrNull(bindingKeyRef)?.accountId

    /** Idempotent: called every time a channel reaches AUTHENTICATED, regardless of intent. */
    fun linkDeviceToAccount(bindingKeyRef: String, accountId: Long) {
        val existing = deviceAccountLinkRepository.findByIdOrNull(bindingKeyRef)
        if (existing == null) {
            deviceAccountLinkRepository.save(DeviceAccountLink(bindingKeyRef, accountId))
        } else if (existing.accountId != accountId) {
            existing.accountId = accountId
            existing.updatedAt = Instant.now()
            deviceAccountLinkRepository.save(existing)
        }
    }

    // ToolSession management -----------------------------------------------------

    fun createToolSession(journeyId: UUID, ttl: Duration): ToolSession =
        toolSessionRepository.save(ToolSession(journeyId, Instant.now().plus(ttl)))

    fun findToolSessionById(toolSessionId: UUID): ToolSession? =
        toolSessionRepository.findByIdOrNull(toolSessionId)
            ?.takeIf { it.isUsable }

    /**
     * Ends a tool session for good as [status] says. Needed at once, not at its TTL: a completed
     * step must not complete twice, and a re-offered candidate can have the same toolId.
     */
    fun endToolSession(toolSessionId: UUID, status: ToolSessionStatus) {
        check(status != ToolSessionStatus.RUNNING) { "endToolSession needs a final status" }
        toolSessionRepository.findByIdOrNull(toolSessionId)?.let { session ->
            session.status = status
            toolSessionRepository.save(session)
        }
    }
}
