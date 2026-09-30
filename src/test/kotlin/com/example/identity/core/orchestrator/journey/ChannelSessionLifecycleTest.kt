package com.example.identity.core.orchestrator.journey

import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.ChannelState
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import com.example.identity.core.orchestrator.kc.KeycloakSessionEnded
import com.example.identity.core.orchestrator.session.AppTokenIssuer
import com.example.identity.core.orchestrator.session.AppTokenSession
import com.example.identity.core.orchestrator.session.AppTokenSessionService
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.session.SessionExpiredException
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.core.orchestrator.session.SessionRefusedException
import com.example.identity.core.orchestrator.session.TokenPair
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.springframework.context.ApplicationEventPublisher
import java.time.Instant
import java.util.UUID

/**
 * Unit test of [ChannelSessionLifecycle] with its collaborators mocked. Checks that only an APP
 * channel touches its Keycloak session, how a lost session is reported, and that [end] clears the
 * tokens, publishes the Keycloak logout and records only a requested sign-out.
 */
class ChannelSessionLifecycleTest : BehaviorSpec({

    class Fixture {
        val appTokenIssuer = mockk<AppTokenIssuer>()
        val appTokenSessionService = mockk<AppTokenSessionService>()
        val sessionManagementService = mockk<SessionManagementService>()
        val journeyRecorder = mockk<JourneyRecorder>(relaxed = true)
        val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
        val lifecycle = ChannelSessionLifecycle(appTokenIssuer, appTokenSessionService, sessionManagementService, journeyRecorder, eventPublisher)

        init {
            every { sessionManagementService.updateChannelSession(any()) } answers { firstArg() }
            every { appTokenSessionService.save(any()) } answers { firstArg() }
        }
    }

    fun channel(type: ChannelType, state: ChannelState = ChannelState.AUTHENTICATED) =
        ChannelSession(channel = type, now = Instant.now()).apply { this.state = state }

    fun tokenPair() = TokenPair("access", Instant.now().plusSeconds(60), Instant.now().plusSeconds(600))

    /** A logged-in context with tokens, as the channel carries it before [ChannelSessionLifecycle.end]. */
    fun loggedInContext(keycloakSessionId: String?) = AppTokenSession(accountId = AccountId(1L), keycloakSessionId = keycloakSessionId, now = Instant.now()).apply {
        appTokenSessionId = UUID.randomUUID()
        accessToken = "access"
        refreshToken = "refresh"
        accessExpiresAt = Instant.now().plusSeconds(60)
        refreshExpiresAt = Instant.now().plusSeconds(600)
    }

    given("open() on a WEB channel") {
        val f = Fixture()
        val channel = channel(ChannelType.WEB)

        `when`("the channel logs in") {
            val gone = f.lifecycle.open(channel)

            then("it fetches no token, since Keycloak opened the session itself") {
                gone.shouldBeNull()
                verify(exactly = 0) { f.appTokenIssuer.tokenFor(any(), any()) }
            }
        }
    }

    given("open() on an APP channel whose token request succeeds") {
        val f = Fixture()
        val channel = channel(ChannelType.APP)
        every { f.appTokenIssuer.tokenFor(channel, any()) } returns tokenPair()

        `when`("the channel logs in") {
            val gone = f.lifecycle.open(channel)

            then("it fetches the token and reports no loss") {
                gone.shouldBeNull()
                verify(exactly = 1) { f.appTokenIssuer.tokenFor(channel, any()) }
            }
        }
    }

    given("open() on an APP channel whose session has expired") {
        val f = Fixture()
        val channel = channel(ChannelType.APP)
        val expired = SessionExpiredException("expired")
        every { f.appTokenIssuer.tokenFor(channel, any()) } throws expired

        `when`("the channel logs in") {
            val gone = f.lifecycle.open(channel)

            then("it reports the session as gone, with the expiry as cause") {
                gone.shouldNotBeNull().cause shouldBeSameInstanceAs expired
            }
        }
    }

    given("open() on an APP channel whose session Keycloak refuses") {
        val f = Fixture()
        val channel = channel(ChannelType.APP)
        every { f.appTokenIssuer.tokenFor(channel, any()) } throws SessionRefusedException("refused")

        `when`("the channel logs in") {
            val result = runCatching { f.lifecycle.open(channel) }

            then("it fails the transition with an invalid state") {
                val e = shouldThrow<OrchestratorException> { result.getOrThrow() }
                e.code shouldBe ErrorCode.INVALID_STATE_TRANSITION
            }
        }
    }

    given("keepAlive() on a logged-in WEB channel") {
        val f = Fixture()
        val channel = channel(ChannelType.WEB)

        `when`("the channel is used") {
            val gone = f.lifecycle.keepAlive(channel)

            then("it renews nothing") {
                gone.shouldBeNull()
                verify(exactly = 0) { f.appTokenIssuer.keepAlive(any()) }
            }
        }
    }

    given("keepAlive() on an anonymous APP channel") {
        val f = Fixture()
        val channel = channel(ChannelType.APP, ChannelState.ANONYMOUS)

        `when`("the channel is used") {
            val gone = f.lifecycle.keepAlive(channel)

            then("it renews nothing, since there is no session yet") {
                gone.shouldBeNull()
                verify(exactly = 0) { f.appTokenIssuer.keepAlive(any()) }
            }
        }
    }

    given("keepAlive() on a logged-in APP channel") {
        val f = Fixture()
        val channel = channel(ChannelType.APP)
        justRun { f.appTokenIssuer.keepAlive(channel) }

        `when`("the channel is used") {
            val gone = f.lifecycle.keepAlive(channel)

            then("it renews the session and reports no loss") {
                gone.shouldBeNull()
                verify(exactly = 1) { f.appTokenIssuer.keepAlive(channel) }
            }
        }
    }

    given("keepAlive() on a logged-in APP channel whose session has expired") {
        val f = Fixture()
        val channel = channel(ChannelType.APP)
        val expired = SessionExpiredException("expired")
        every { f.appTokenIssuer.keepAlive(channel) } throws expired

        `when`("the channel is used") {
            val gone = f.lifecycle.keepAlive(channel)

            then("it reports the session as gone") {
                gone.shouldNotBeNull().cause shouldBeSameInstanceAs expired
            }
        }
    }

    given("keepAlive() on a logged-in APP channel whose session Keycloak refuses") {
        val f = Fixture()
        val channel = channel(ChannelType.APP)
        val refused = SessionRefusedException("refused")
        every { f.appTokenIssuer.keepAlive(channel) } throws refused

        `when`("the channel is used") {
            val gone = f.lifecycle.keepAlive(channel)

            then("it reports the session as gone, unlike open()") {
                gone.shouldNotBeNull().cause shouldBeSameInstanceAs refused
            }
        }
    }

    given("end() with a state that is not terminal") {
        val f = Fixture()
        val channel = channel(ChannelType.APP)

        `when`("ending the channel as AUTHENTICATED") {
            val result = runCatching { f.lifecycle.end(channel, ChannelState.AUTHENTICATED) }

            then("it refuses with IllegalStateException") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("end() on a logged-in APP channel with a Keycloak session") {
        val f = Fixture()
        val context = loggedInContext(keycloakSessionId = "kc-session-1")
        val channel = channel(ChannelType.APP).apply {
            appTokenSessionId = context.appTokenSessionId
            sessionEvidenceId = SessionEvidenceId(UUID.randomUUID())
        }
        every { f.appTokenSessionService.getAppTokenSession(context.appTokenSessionId!!) } returns context

        `when`("the holder logs out") {
            f.lifecycle.end(channel, ChannelState.LOGGED_OUT)

            then("it records the sign-out as the holder's") {
                verify(exactly = 1) { f.journeyRecorder.recordSignOut(channel, endedBy = "HOLDER") }
            }

            then("it publishes the end of exactly this Keycloak session") {
                verify(exactly = 1) { f.eventPublisher.publishEvent(KeycloakSessionEnded("kc-session-1")) }
            }

            then("it clears and saves the tokens") {
                context.accessToken.shouldBeNull()
                context.refreshToken.shouldBeNull()
                context.accessExpiresAt.shouldBeNull()
                context.refreshExpiresAt.shouldBeNull()
                verify(exactly = 1) { f.appTokenSessionService.save(context) }
            }

            then("the channel ends in the final state, detached from context and evidence") {
                channel.state shouldBe ChannelState.LOGGED_OUT
                channel.appTokenSessionId.shouldBeNull()
                channel.sessionEvidenceId.shouldBeNull()
                verify(exactly = 1) { f.sessionManagementService.updateChannelSession(channel) }
            }
        }
    }

    given("end() on an APP channel whose session expired") {
        val f = Fixture()
        val context = loggedInContext(keycloakSessionId = "kc-session-2")
        val channel = channel(ChannelType.APP).apply { appTokenSessionId = context.appTokenSessionId }
        every { f.appTokenSessionService.getAppTokenSession(context.appTokenSessionId!!) } returns context

        `when`("the channel expires") {
            f.lifecycle.end(channel, ChannelState.EXPIRED)

            then("it records no sign-out, since an expiry is no event") {
                verify(exactly = 0) { f.journeyRecorder.recordSignOut(any(), any()) }
            }

            then("it still ends the Keycloak session and clears the tokens") {
                verify(exactly = 1) { f.eventPublisher.publishEvent(KeycloakSessionEnded("kc-session-2")) }
                context.refreshToken.shouldBeNull()
                channel.state shouldBe ChannelState.EXPIRED
            }
        }
    }

    given("end() on a logged-in WEB channel") {
        val f = Fixture()
        val context = loggedInContext(keycloakSessionId = "kc-session-3")
        val channel = channel(ChannelType.WEB).apply { appTokenSessionId = context.appTokenSessionId }
        every { f.appTokenSessionService.getAppTokenSession(context.appTokenSessionId!!) } returns context

        `when`("the holder logs out") {
            f.lifecycle.end(channel, ChannelState.LOGGED_OUT)

            then("it leaves the Web channel's Keycloak session to Keycloak") {
                verify(exactly = 0) { f.eventPublisher.publishEvent(any<Any>()) }
            }

            then("it still clears the tokens and ends the channel") {
                context.accessToken.shouldBeNull()
                context.refreshToken.shouldBeNull()
                channel.state shouldBe ChannelState.LOGGED_OUT
                channel.appTokenSessionId.shouldBeNull()
            }
        }
    }
})
