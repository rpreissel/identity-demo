package com.example.identity.core.orchestrator.session

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.ChannelType
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Unit test of [AppTokenIssuer]: every token moves the channel's expiry to the session window, and a
 * journey interaction renews the session only once a quarter of the window since the last token is
 * used up ([AppTokenIssuer.RENEWAL_WINDOW_SHARE]).
 */
class AppTokenIssuerTest : BehaviorSpec({

    val renewedWindow = TEST_NOW.plus(Duration.ofMinutes(30))

    given("an authenticated App channel whose last token was minted 10 minutes ago, with 20 minutes of window left") {
        val fixture = AppTokenIssuerFixture(issuedAt = TEST_NOW.minus(Duration.ofMinutes(10)), windowEnd = TEST_NOW.plus(Duration.ofMinutes(20)))
        every { fixture.tokenProvider.tokenFor(fixture.channel, any()) } returns TokenPair("renewed", TEST_NOW.plusSeconds(300), renewedWindow)

        `when`("a journey interaction keeps the session alive") {
            fixture.issuer.keepAlive(fixture.channel)

            then("a third of the window is used, so the session is renewed regardless of the cached token") {
                verify { fixture.tokenProvider.tokenFor(fixture.channel, match { it >= Duration.ofHours(1).seconds }) }
            }

            then("the channel's expiry moves to the renewed window") {
                fixture.channel.expiresAt shouldBe renewedWindow
                verify { fixture.sessionManagementService.updateChannelSession(fixture.channel) }
            }
        }
    }

    given("an authenticated App channel whose last token was minted a minute ago, with 29 minutes of window left") {
        val windowEnd = TEST_NOW.plus(Duration.ofMinutes(29))
        val fixture = AppTokenIssuerFixture(issuedAt = TEST_NOW.minus(Duration.ofMinutes(1)), windowEnd = windowEnd)

        `when`("a journey interaction keeps the session alive") {
            fixture.issuer.keepAlive(fixture.channel)

            then("the current window stands: no token is requested, the expiry stays") {
                verify(exactly = 0) { fixture.tokenProvider.tokenFor(any(), any()) }
                fixture.channel.expiresAt shouldBe windowEnd
            }
        }
    }

    given("a channel without an AppTokenSession") {
        val fixture = AppTokenIssuerFixture(issuedAt = null, windowEnd = null, withSession = false)

        `when`("a journey interaction keeps the session alive") {
            fixture.issuer.keepAlive(fixture.channel)

            then("there is nothing to renew") {
                verify(exactly = 0) { fixture.tokenProvider.tokenFor(any(), any()) }
            }
        }
    }

    given("an authenticated App channel") {
        val fixture = AppTokenIssuerFixture(issuedAt = TEST_NOW, windowEnd = TEST_NOW.plus(Duration.ofMinutes(1)))
        every { fixture.tokenProvider.tokenFor(fixture.channel, 15) } returns TokenPair("fresh", TEST_NOW.plusSeconds(300), renewedWindow)

        `when`("a token is requested") {
            val pair = fixture.issuer.tokenFor(fixture.channel, minValiditySeconds = 15)

            then("the provider's token is handed out, and the channel lives exactly as long as its window") {
                pair.accessToken shouldBe "fresh"
                fixture.channel.expiresAt shouldBe renewedWindow
                verify { fixture.sessionManagementService.updateChannelSession(fixture.channel) }
            }
        }
    }
})

/** An APP channel whose AppTokenSession holds a token minted at [issuedAt] and a window ending at [windowEnd]. */
private class AppTokenIssuerFixture(issuedAt: Instant?, windowEnd: Instant?, withSession: Boolean = true) {
    private val appTokenSessionId = UUID.randomUUID()
    val channel = ChannelSession(channel = ChannelType.APP, now = TEST_NOW).also {
        it.appTokenSessionId = appTokenSessionId.takeIf { withSession }
        it.expiresAt = windowEnd ?: TEST_NOW
    }
    val tokenProvider = mockk<TokenProvider>()
    private val appTokenSessionService = mockk<AppTokenSessionService> {
        every { getAppTokenSession(appTokenSessionId) } returns AppTokenSession(accountId = AccountId(7L), now = TEST_NOW).apply {
            accessToken = issuedAt?.let { PlainJWT(JWTClaimsSet.Builder().issueTime(Date.from(it)).build()).serialize() }
            refreshExpiresAt = windowEnd
        }
    }
    val sessionManagementService = mockk<SessionManagementService> { every { updateChannelSession(any()) } answers { firstArg() } }
    val issuer = AppTokenIssuer(tokenProvider, appTokenSessionService, sessionManagementService, plainTokenVault, TEST_CLOCK)
}
