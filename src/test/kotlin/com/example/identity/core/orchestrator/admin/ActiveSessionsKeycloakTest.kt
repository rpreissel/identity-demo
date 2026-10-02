package com.example.identity.core.orchestrator.admin

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.keycloak.KeycloakClientSessions
import com.example.identity.core.orchestrator.keycloak.KeycloakSessionClient
import com.example.identity.core.orchestrator.keycloak.KeycloakUserSession
import com.example.identity.core.orchestrator.keycloak.KeycloakUserSessions
import com.example.identity.core.orchestrator.session.AppTokenSession
import com.example.identity.core.orchestrator.session.AppTokenSessionRepository
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.ChannelSessionRepository
import com.example.identity.kcmigrate.federatedUserId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.util.UUID

/** Keycloak's half of [ActiveSessions], against a fake instead of a running Keycloak. */
class ActiveSessionsKeycloakTest : BehaviorSpec({

    val start = Instant.parse("2026-09-26T10:00:00Z")

    /** [ActiveSessions] over relaxed repositories; account 7 is bound to "Mara Muster", no other account exists. */
    class Fixture(source: KeycloakUserSessions?) {
        val channels = mockk<ChannelSessionRepository>(relaxed = true)
        val appTokenSessions = mockk<AppTokenSessionRepository>(relaxed = true)
        private val accountService = mockk<AccountService> {
            every { findAccount(any()) } returns null
            every { findAccount(AccountId(7)) } returns AccountProfile(AccountId(7), PartnerNumber("P000000007"), emptyList())
        }
        private val personDirectory = mockk<PersonDirectory> { every { displayName(PartnerNumber("P000000007")) } returns "Mara Muster" }
        val service = ActiveSessions(
            channels, appTokenSessions, accountService, personDirectory,
            mockk<ObjectProvider<KeycloakUserSessions>> { every { ifAvailable } returns source },
            clock = TEST_CLOCK,
        )
    }

    fun session(id: String, userId: String?) = KeycloakUserSession(id, "user-$id", userId, start, start.plusSeconds(60))

    given("Keycloak sessions of the app and the website, the website one run twice through the flow") {
        val fake = KeycloakUserSessions {
            mapOf(
                KeycloakSessionClient.APP to KeycloakClientSessions("app-token", 1, listOf(session("kc-app", federatedUserId(7)))),
                KeycloakSessionClient.WEBSITE to KeycloakClientSessions(
                    "browser", 14, listOf(session("kc-web", federatedUserId(8)), session("kc-other", "local-user")),
                ),
            )
        }
        val fixture = Fixture(fake)
        val appContext = AppTokenSession(accountId = AccountId(7), keycloakSessionId = "kc-app", now = TEST_NOW)
            .apply { appTokenSessionId = UUID.randomUUID() }
        val appChannel = ChannelSession(ChannelType.APP, "key", start.plusSeconds(600), now = TEST_NOW).apply {
            state = ChannelState.AUTHENTICATED
            appTokenSessionId = appContext.appTokenSessionId
        }
        val older = ChannelSession(ChannelType.WEB, null, start.plusSeconds(600), now = TEST_NOW).apply {
            durableKeycloakSessionId = "kc-web"
            createdAt = start
        }
        val newer = ChannelSession(ChannelType.WEB, null, start.plusSeconds(600), now = TEST_NOW).apply {
            durableKeycloakSessionId = "kc-web"
            state = ChannelState.AUTHENTICATED
            createdAt = start.plusSeconds(5)
        }
        every { fixture.appTokenSessions.findByKeycloakSessionIdIn(listOf("kc-app")) } returns listOf(appContext)
        every { fixture.channels.findByAppTokenSessionIdIn(listOf(appContext.appTokenSessionId!!)) } returns listOf(appChannel)
        every { fixture.channels.findByDurableKeycloakSessionIdIn(listOf("kc-web", "kc-other")) } returns listOf(older, newer)

        `when`("the report is built") {
            val keycloak = fixture.service.report().keycloak!!

            then("it groups by client, website first, without an error") {
                keycloak.error.shouldBeNull()
                keycloak.clients.map { it.client } shouldBe listOf(KeycloakSessionClient.WEBSITE, KeycloakSessionClient.APP)
            }

            then("the website sessions name their accounts, and the later of two flow runs stands for its Keycloak session") {
                val website = keycloak.clients[0]
                website.count shouldBe 14
                website.newest.map { it.accountId } shouldBe listOf(AccountId(8L), null)
                website.newest[0].channelSessionId shouldBe newer.channelSessionId
                website.newest[1].channelSessionId.shouldBeNull()
            }

            then("the app session names the account and finds its channel") {
                val app = keycloak.clients[1].newest.single()
                app.accountId shouldBe AccountId(7)
                app.displayName shouldBe "Mara Muster"
                app.channelSessionId shouldBe appChannel.channelSessionId
                app.channelState shouldBe ChannelState.AUTHENTICATED
            }
        }
    }

    given("an unreachable Keycloak and three app channels") {
        val fixture = Fixture { error("Connection refused") }
        every { fixture.channels.countByChannelAndStateNotInAndExpiresAtAfter(ChannelType.APP, any(), any()) } returns 3

        `when`("the report is built") {
            val report = fixture.service.report()

            then("the error is reported, the channels still are") {
                report.channels.total shouldBe 3
                report.keycloak!!.clients.shouldBeEmpty()
                report.keycloak.error shouldBe "Connection refused"
            }
        }
    }

    given("no keycloak profile") {
        val fixture = Fixture(source = null)

        `when`("the report is built") {
            val report = fixture.service.report()

            then("there is no Keycloak block") {
                report.keycloak.shouldBeNull()
            }
        }
    }
})
