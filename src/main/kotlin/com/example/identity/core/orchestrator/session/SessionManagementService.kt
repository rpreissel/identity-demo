package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.Subject
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.AuthIntent
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.util.UUID
import com.example.identity.core.orchestrator.domain.AcrLevels

@Service
@Transactional
class SessionManagementService(
    private val channelSessionRepository: ChannelSessionRepository,
    private val toolSessionRepository: ToolSessionRepository,
    private val deviceAccountLinkRepository: DeviceAccountLinkRepository,
    private val clock: Clock
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
        accountId: AccountId?
    ): ChannelSession {
        val now = clock.instant()
        val session = ChannelSession(channel, bindingKeyRef, now.plus(ttl), now)
        session.subject = accountId?.let(Subject::Account)
        return channelSessionRepository.save(session)
    }

    /**
     * WEB channel creation for an upsert (docs/05-api.md Abschnitt 3b). The id is chosen by
     * Keycloak; the caller has checked it is unused. `entryIntent` is [AuthIntent.WEB_SELECT_METHOD]
     * or [AuthIntent.REGISTER]; the other entry intents assume an App channel. [availableTools] is
     * the extension's declaration of what it can render, as on the App channel.
     */
    fun createWebChannelSession(
        channelSessionId: ChannelSessionId,
        channelBinding: String,
        accountId: AccountId?,
        ttl: Duration,
        availableTools: Set<String>,
        entryIntent: AuthIntent = AuthIntent.WEB_SELECT_METHOD
    ): ChannelSession {
        val now = clock.instant()
        val session = ChannelSession(ChannelType.WEB, null, now.plus(ttl), now)
        session.channelSessionId = channelSessionId
        session.channelBinding = channelBinding
        session.subject = accountId?.let(Subject::Account)
        session.entryIntent = entryIntent
        session.availableClientTools = availableTools.toMutableSet()
        return channelSessionRepository.save(session)
    }

    fun findChannelSessionById(channelSessionId: ChannelSessionId): ChannelSession? =
        channelSessionRepository.findByChannelSessionId(channelSessionId)
            ?.takeIf { !it.isExpiredAt(clock.instant()) }

    /** The channel this request works on, read again after a write. Gone only if it expired meanwhile. */
    fun reloadChannelSession(channelSessionId: ChannelSessionId): ChannelSession =
        checkNotNull(findChannelSessionById(channelSessionId)) { "Channel $channelSessionId vanished mid-request" }

    fun updateChannelSession(session: ChannelSession): ChannelSession {
        session.touch(clock.instant())
        return channelSessionRepository.save(session)
    }

    fun updateChannelState(channelSessionId: ChannelSessionId, newState: ChannelState) {
        channelSessionRepository.findByChannelSessionId(channelSessionId)?.let { session ->
            session.state = newState
            session.touch(clock.instant())
            channelSessionRepository.save(session)
        }
    }

    /**
     * Only raises, never lowers (docs/05-api.md, step-ups). Compared against the effective floor,
     * so an explicit "loa1" cannot undercut the implicit loa2 baseline.
     */
    fun raiseChannelAcrFloor(channelSessionId: ChannelSessionId, requiredAcr: String) {
        channelSessionRepository.findByChannelSessionId(channelSessionId)?.let { session ->
            val effectiveFloor = session.acrFloor ?: AcrLevels.DEFAULT_REQUIRED_ACR.value
            session.acrFloor = AcrLevels.max(effectiveFloor, requiredAcr)
            session.touch(clock.instant())
            channelSessionRepository.save(session)
        }
    }

    fun bindAccountAndAppTokenSession(channelSessionId: ChannelSessionId, accountId: AccountId, appTokenSessionId: UUID) {
        channelSessionRepository.findByChannelSessionId(channelSessionId)?.let { session ->
            session.subject = accountId?.let(Subject::Account)
            session.appTokenSessionId = appTokenSessionId
            session.touch(clock.instant())
            channelSessionRepository.save(session)
        }
    }

    // Device-account link management --------------------------------------------

    /** Known account for this device, if any. */
    fun findLinkedAccountId(bindingKeyRef: String): AccountId? =
        deviceAccountLinkRepository.findByIdOrNull(bindingKeyRef)?.accountId

    /** Idempotent: called every time a channel reaches AUTHENTICATED, regardless of intent. */
    fun linkDeviceToAccount(bindingKeyRef: String, accountId: AccountId) {
        val existing = deviceAccountLinkRepository.findByIdOrNull(bindingKeyRef)
        if (existing == null) {
            deviceAccountLinkRepository.save(DeviceAccountLink(bindingKeyRef, accountId, clock.instant()))
        } else if (existing.accountId != accountId) {
            existing.accountId = accountId
            existing.updatedAt = clock.instant()
            deviceAccountLinkRepository.save(existing)
        }
    }

    // ToolSession management -----------------------------------------------------

    fun createToolSession(journeyId: JourneyId, ttl: Duration): ToolSession {
        val now = clock.instant()
        return toolSessionRepository.save(ToolSession(journeyId, now.plus(ttl), now))
    }

    fun findToolSessionById(toolSessionId: ToolSessionId): ToolSession? =
        toolSessionRepository.findByToolSessionId(toolSessionId)
            ?.takeIf { it.isUsableAt(clock.instant()) }

    /**
     * Ends a tool session for good as [status] says. Needed at once, not at its TTL: a completed
     * step must not complete twice, and a re-offered candidate can have the same toolId.
     */
    fun endToolSession(toolSessionId: ToolSessionId, status: ToolSessionStatus) {
        check(status != ToolSessionStatus.RUNNING) { "endToolSession needs a final status" }
        toolSessionRepository.findByToolSessionId(toolSessionId)?.let { session ->
            session.status = status
            // A finished run needs its working data no more; some of it is personal (ident-fsc), so
            // it goes now rather than with the retention sweep a day later (ADR-49).
            session.data = null
            session.dataType = null
            toolSessionRepository.save(session)
        }
    }
}
