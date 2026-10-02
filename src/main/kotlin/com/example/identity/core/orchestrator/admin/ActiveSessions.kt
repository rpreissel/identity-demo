package com.example.identity.core.orchestrator.admin

import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.account.AccountService
import com.example.identity.kcmigrate.accountIdOfFederatedUser
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.keycloak.KeycloakClientSessions
import com.example.identity.core.orchestrator.keycloak.KeycloakSessionClient
import com.example.identity.core.orchestrator.keycloak.KeycloakUserSessions
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.AppTokenSessionRepository
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
import com.example.identity.core.orchestrator.session.channelType
import com.example.identity.core.orchestrator.session.id
import com.example.identity.contract.tool_api.directory.PersonDirectory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

data class ChannelTypeCount(val channel: ChannelType, val count: Long)

/** One live orchestrator channel; [displayName] as far as the register names the account's person. */
data class ActiveChannelView(
    val channelSessionId: ChannelSessionId,
    val channel: ChannelType,
    val state: ChannelState,
    val accountId: AccountId?,
    val displayName: String?,
    val createdAt: Instant,
    val lastAccessedAt: Instant,
    val expiresAt: Instant,
)

/** Live channels: neither logged out nor expired. [newest] by creation, at most [ActiveSessions.NEWEST]. */
data class ActiveChannelsView(
    val total: Long,
    val perType: List<ChannelTypeCount>,
    val newest: List<ActiveChannelView>,
)

/** One open Keycloak session, with the orchestrator channel that belongs to it if one is known. */
data class KeycloakSessionView(
    val sessionId: String,
    val username: String?,
    val accountId: AccountId?,
    val displayName: String?,
    val start: Instant,
    val lastAccess: Instant,
    val channelSessionId: ChannelSessionId?,
    val channelState: ChannelState?,
)

data class KeycloakClientSessionsView(
    val client: KeycloakSessionClient,
    val clientId: String,
    val count: Long,
    val newest: List<KeycloakSessionView>,
)

/** Keycloak's side; [clients] is empty when [error] says why Keycloak could not be asked. */
data class KeycloakSessionsView(val clients: List<KeycloakClientSessionsView>, val error: String?)

data class ActiveSessionsView(
    val channels: ActiveChannelsView,
    /** Null without the `keycloak` profile. */
    val keycloak: KeycloakSessionsView?,
)

/**
 * Who is using the demo right now: the orchestrator's live channels and Keycloak's open sessions
 * (docs/05-api.md Abschnitt 1). Read before a reset, which ends the sessions of every account.
 * Not transactional: the Keycloak part is a network round trip.
 */
@Service
class ActiveSessions(
    private val channelSessionRepository: ChannelSessionRepository,
    private val appTokenSessionRepository: AppTokenSessionRepository,
    private val accountService: AccountService,
    private val personDirectory: PersonDirectory,
    private val keycloakUserSessions: ObjectProvider<KeycloakUserSessions>,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(ActiveSessions::class.java)

    fun report(): ActiveSessionsView = ActiveSessionsView(channels(), keycloakUserSessions.ifAvailable?.let { keycloak(it) })

    private fun channels(): ActiveChannelsView {
        val now = clock.instant()
        val perType = ChannelType.entries.map { type ->
            ChannelTypeCount(type, channelSessionRepository.countByChannelAndStateNotInAndExpiresAtAfter(type, TERMINAL, now))
        }
        val newest = channelSessionRepository.findByStateNotInAndExpiresAtAfter(
            TERMINAL, now, PageRequest.of(0, NEWEST, Sort.by(Sort.Direction.DESC, "createdAt")),
        )
        return ActiveChannelsView(perType.sumOf { it.count }, perType, newest.map { it.toView() })
    }

    private fun keycloak(source: KeycloakUserSessions): KeycloakSessionsView {
        val sessions = try {
            source.openSessions(NEWEST)
        } catch (e: Exception) {
            log.warn("Keycloak-Sitzungen nicht lesbar: {}", e.toString())
            return KeycloakSessionsView(emptyList(), e.message ?: e.javaClass.simpleName)
        }
        return KeycloakSessionsView(
            KeycloakSessionClient.entries.mapNotNull { client -> sessions[client]?.let { it.toView(client) } },
            error = null,
        )
    }

    private fun KeycloakClientSessions.toView(client: KeycloakSessionClient): KeycloakClientSessionsView {
        val ids = newest.map { it.sessionId }
        // The link each channel type keeps to its Keycloak session (ChannelSession, AppTokenSession).
        val channels = if (ids.isEmpty()) emptyList() else when (client) {
            KeycloakSessionClient.APP -> {
                val sessionByContext = appTokenSessionRepository.findByKeycloakSessionIdIn(ids)
                    .associate { it.appTokenSessionId to it.keycloakSessionId }
                channelSessionRepository.findByAppTokenSessionIdIn(sessionByContext.keys.filterNotNull())
                    .map { sessionByContext[it.appTokenSessionId] to it }
            }
            KeycloakSessionClient.WEBSITE -> channelSessionRepository.findByDurableKeycloakSessionIdIn(ids)
                .map { it.durableKeycloakSessionId to it }
        }
        // Several flow runs can share one Keycloak session; the latest stands for it.
        val channelBySession = channels.groupBy({ it.first }, { it.second })
            .mapValues { (_, list) -> list.maxBy { it.createdAt ?: Instant.MIN } }
        return KeycloakClientSessionsView(
            client = client,
            clientId = clientId,
            count = count,
            newest = newest.map { session ->
                val accountId = session.userId?.let { accountIdOfFederatedUser(it) }
                val channel = channelBySession[session.sessionId]
                KeycloakSessionView(
                    sessionId = session.sessionId,
                    username = session.username,
                    accountId = accountId?.let(::AccountId),
                    displayName = accountId?.let { displayName(AccountId(it)) },
                    start = session.start,
                    lastAccess = session.lastAccess,
                    channelSessionId = channel?.id,
                    channelState = channel?.let(::shownState),
                )
            },
        )
    }

    private fun ChannelSession.toView() = ActiveChannelView(
        channelSessionId = id,
        channel = channelType,
        state = shownState(this),
        accountId = accountId,
        displayName = accountId?.let { displayName(it) },
        createdAt = checkNotNull(createdAt),
        lastAccessedAt = checkNotNull(lastAccessedAt),
        expiresAt = checkNotNull(expiresAt),
    )

    /** As the channel's own responses show it (ChannelState.shownWith, ADR-46). */
    private fun shownState(channel: ChannelSession): ChannelState =
        checkNotNull(channel.state).shownWith(channel.accountId?.let { accountService.isBeingSetUp(it) } == true)

    private fun displayName(accountId: AccountId): String? =
        accountService.findAccount(accountId)?.personId?.let { personDirectory.displayName(it) }

    companion object {
        /** How many sessions each list shows. */
        const val NEWEST = 10

        /** What the report holds, for the OpenAPI description of both endpoints. */
        const val DESCRIPTION =
            "Channels that are neither logged out nor expired, counted per type, and the newest 10 of them. " +
                "Under the keycloak profile also Keycloak's open sessions of the website client and the app token client, " +
                "each with the channel it belongs to if known; if Keycloak cannot be asked, `keycloak.error` says why."

        private val TERMINAL = ChannelState.entries.filter { it.isTerminal }
    }
}
