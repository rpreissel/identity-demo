package com.example.identity.core.orchestrator.admin

import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.kcmigrate.federatedUserId
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.kc.KeycloakClientSessions
import com.example.identity.core.orchestrator.kc.KeycloakSessionClient
import com.example.identity.core.orchestrator.kc.KeycloakUserSession
import com.example.identity.core.orchestrator.kc.KeycloakUserSessions
import com.example.identity.core.orchestrator.session.AuthContext
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
import com.example.identity.contract.tool_api.directory.PersonDirectory
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

/** Keycloak's half of [ActiveSessions], against a fake instead of a running Keycloak. */
class ActiveSessionsKeycloakTest {

    private val repository = mockk<ChannelSessionRepository>(relaxed = true)
    private val accountService = mockk<AccountService> {
        every { findAccount(any()) } returns null
        every { findAccount(7) } returns AccountProfile(7, "p-7", emptyList())
    }
    private val personDirectory = mockk<PersonDirectory> { every { displayName("p-7") } returns "Mara Muster" }

    private fun service(source: KeycloakUserSessions?) = ActiveSessions(
        repository, accountService, personDirectory,
        mockk<ObjectProvider<KeycloakUserSessions>> { every { ifAvailable } returns source },
    )

    private val start = Instant.parse("2026-09-26T10:00:00Z")

    private fun session(id: String, userId: String?) = KeycloakUserSession(id, "user-$id", userId, start, start.plusSeconds(60))

    @Test
    fun `groups by client, names the account and finds the channel of each session`() {
        val appChannel = ChannelSession(ChannelType.APP, "key", start.plusSeconds(600)).apply {
            state = ChannelState.AUTHENTICATED
            authContext = AuthContext(accountId = 7, keycloakSessionId = "kc-app")
        }
        val older = ChannelSession(ChannelType.KEYCLOAK, null, start.plusSeconds(600)).apply {
            durableKcSessionId = "kc-web"
            createdAt = start
        }
        val newer = ChannelSession(ChannelType.KEYCLOAK, null, start.plusSeconds(600)).apply {
            durableKcSessionId = "kc-web"
            state = ChannelState.AUTHENTICATED
            createdAt = start.plusSeconds(5)
        }
        every { repository.findByAuthContextKeycloakSessionIdIn(listOf("kc-app")) } returns listOf(appChannel)
        every { repository.findByDurableKcSessionIdIn(listOf("kc-web", "kc-other")) } returns listOf(older, newer)
        val fake = KeycloakUserSessions {
            mapOf(
                KeycloakSessionClient.APP to KeycloakClientSessions("app-token", 1, listOf(session("kc-app", federatedUserId(7)))),
                KeycloakSessionClient.WEBSITE to KeycloakClientSessions(
                    "browser", 14, listOf(session("kc-web", federatedUserId(8)), session("kc-other", "local-user")),
                ),
            )
        }

        val keycloak = service(fake).report().keycloak!!

        assertThat(keycloak.error).isNull()
        assertThat(keycloak.clients.map { it.client }).containsExactly(KeycloakSessionClient.WEBSITE, KeycloakSessionClient.APP)
        val (website, app) = keycloak.clients
        assertThat(website.count).isEqualTo(14)
        assertThat(website.newest.map { it.accountId }).containsExactly(8L, null)
        // Two flow runs of one Keycloak session: the later one stands for it.
        assertThat(website.newest[0].channelSessionId).isEqualTo(newer.channelSessionId)
        assertThat(website.newest[1].channelSessionId).isNull()
        with(app.newest.single()) {
            assertThat(accountId).isEqualTo(7L)
            assertThat(displayName).isEqualTo("Mara Muster")
            assertThat(channelSessionId).isEqualTo(appChannel.channelSessionId)
            assertThat(channelState).isEqualTo(ChannelState.AUTHENTICATED)
        }
    }

    @Test
    fun `an unreachable Keycloak is reported, the channels still are`() {
        every { repository.countByChannelAndStateNotInAndExpiresAtAfter(ChannelType.APP, any(), any()) } returns 3

        val report = service { error("Connection refused") }.report()

        assertThat(report.channels.total).isEqualTo(3)
        assertThat(report.keycloak!!.clients).isEmpty()
        assertThat(report.keycloak.error).isEqualTo("Connection refused")
    }

    @Test
    fun `without the keycloak profile there is no Keycloak block`() {
        assertThat(service(null).report().keycloak).isNull()
    }
}
