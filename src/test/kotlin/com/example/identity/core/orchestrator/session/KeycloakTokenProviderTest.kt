package com.example.identity.core.orchestrator.session

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.keycloak.AccountTokenResponse
import com.example.identity.core.orchestrator.keycloak.KeycloakAdminClient
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.Instant
import java.util.Optional
import java.util.UUID

private const val KEYCLOAK_SESSION = "kc-session"
private const val EXISTING_REFRESH = "existing-refresh"

/**
 * Unit test of [KeycloakTokenProvider], the `keycloak`-profile [TokenProvider]: the still-valid short-circuit
 * matches [TokenService]'s, a token with unchanged evidence is renewed via Keycloak's `refresh_token`
 * grant, and a fresh mint sends the current acr/amr to the account-token grant (ADR-9, addendum F-6).
 */
class KeycloakTokenProviderTest : BehaviorSpec({

    given("an AccessToken that still has well over minValiditySeconds left") {
        val fixture = KeycloakTokenFixture(storedAppTokenSession(accountId = AccountId(42L), now = TEST_NOW).apply {
            accessToken = "existing-token"; accessExpiresAt = TEST_NOW.plusSeconds(300)
        })

        `when`("a token is requested with 15 seconds minimum validity") {
            val result = fixture.tokenFor(minValiditySeconds = 15)

            then("it is returned unchanged - no grant call") {
                result.accessToken shouldBe "existing-token"
                verify(exactly = 0) { fixture.keycloak.requestAccountToken(any(), any(), any(), any()) }
                verify(exactly = 0) { fixture.keycloak.refreshAccountToken(any()) }
            }
        }
    }

    given("an expiring AccessToken whose RefreshToken is still valid (no step-up in between)") {
        val fixture = KeycloakTokenFixture(expiredWithRefreshUntil(TEST_NOW.plusSeconds(600)))
        every { fixture.keycloak.refreshAccountToken(EXISTING_REFRESH) } returns
            AccountTokenResponse("renewed-access-token", 300, "rotated-refresh", 600)

        `when`("a token is requested") {
            val result = fixture.tokenFor()

            then("it is renewed via Keycloak's own refresh_token grant, rotating the RefreshToken") {
                result.accessToken shouldBe "renewed-access-token"
                fixture.session.refreshToken shouldBe "rotated-refresh"
                verify(exactly = 0) { fixture.keycloak.requestAccountToken(any(), any(), any(), any()) }
            }
        }
    }

    given("an expiring AccessToken whose RefreshToken window has lapsed") {
        val fixture = KeycloakTokenFixture(expiredWithRefreshUntil(TEST_NOW.minusSeconds(1)))

        `when`("a token is requested") {
            val result = runCatching { fixture.tokenFor() }

            then("the login ends - no new Keycloak session is opened behind Keycloak's back") {
                shouldThrow<SessionExpiredException> { result.getOrThrow() }
                verify(exactly = 0) { fixture.keycloak.requestAccountToken(any(), any(), any(), any()) }
            }
        }
    }

    given("an expiring AccessToken whose refresh Keycloak refuses (its session ended)") {
        val fixture = KeycloakTokenFixture(expiredWithRefreshUntil(TEST_NOW.plusSeconds(600)))
        every { fixture.keycloak.refreshAccountToken(EXISTING_REFRESH) } throws badRequest("invalid_grant")

        `when`("a token is requested") {
            val result = runCatching { fixture.tokenFor() }

            then("the login ends too") {
                shouldThrow<SessionExpiredException> { result.getOrThrow() }
                verify(exactly = 0) { fixture.keycloak.requestAccountToken(any(), any(), any(), any()) }
            }
        }
    }

    given("the first token of a login, with evidence the policy rates loa2") {
        val accountId = AccountId(7)
        val sessionEvidenceId = SessionEvidenceId(UUID.randomUUID())
        val fixture = KeycloakTokenFixture(storedAppTokenSession(accountId = accountId, now = TEST_NOW).apply {
            accessToken = "stale"; accessExpiresAt = TEST_NOW.minusSeconds(5)
            this.sessionEvidenceId = sessionEvidenceId
        })
        val token = keycloakToken(sid = "kc-session-7")
        every { fixture.keycloak.requestAccountToken(accountId, "loa2", any(), null) } returns
            AccountTokenResponse(token, 300, "fresh-refresh", 600)
        every { fixture.authPolicy.resolveAcr(any(), any()) } returns AcrLevel.LOA2
        every { fixture.sessionEvidenceService.getSessionEvidence(sessionEvidenceId) } returns
            SessionEvidenceRecord(Subject.Account(accountId), TEST_NOW)
        every { fixture.accountService.findAccount(accountId) } returns AccountProfile(
            accountId = accountId, personId = PartnerNumber("P000000001"),
            authenticationMethods = listOf(
                AuthMethodView(
                    id = "m1", method = "password", active = true, createdAt = TEST_NOW, enrolledUnderAcr = "loa1",
                    boundKeyRef = null, reference = null, enrollmentRef = EnrollmentRef("auth_password.enrollment", "1")
                )
            )
        )

        `when`("a token is requested") {
            val result = fixture.tokenFor()

            then("the account-token grant opens a session, and the token carries the current acr") {
                result.accessToken shouldBe token
                verify { fixture.keycloak.requestAccountToken(accountId, "loa2", any(), null) }
            }

            then("the session caches both tokens and remembers Keycloak's session id") {
                fixture.session.accessToken shouldBe token
                fixture.session.refreshToken shouldBe "fresh-refresh"
                fixture.session.keycloakSessionId shouldBe "kc-session-7"
            }
        }
    }

    given("a first token that Keycloak refuses to mint (ADR-43)") {
        val fixture = KeycloakTokenFixture(storedAppTokenSession(accountId = AccountId(9L), now = TEST_NOW))
        every { fixture.keycloak.requestAccountToken(AccountId(9L), any(), any(), null) } throws badRequest("invalid_request")

        `when`("a token is requested") {
            val result = runCatching { fixture.tokenFor() }

            then("no session was opened: SessionRefusedException, and nothing is cached") {
                shouldThrow<SessionRefusedException> { result.getOrThrow() }
                fixture.session.accessToken.shouldBeNull()
                fixture.session.refreshToken.shouldBeNull()
            }
        }
    }

    // A step-up cleared the cached tokens of a login whose Keycloak session is open (ADR-43).
    given("a login after a step-up, its session window still open") {
        val fixture = KeycloakTokenFixture(afterStepUp(window = TEST_NOW.plusSeconds(600)))
        every { fixture.keycloak.requestAccountToken(AccountId(5L), any(), any(), KEYCLOAK_SESSION) } returns
            AccountTokenResponse(keycloakToken(sid = KEYCLOAK_SESSION), 300, "fresh-refresh", 500)

        `when`("a token is requested") {
            fixture.tokenFor()

            then("the grant continues exactly that session - it never opens a second one") {
                fixture.session.keycloakSessionId shouldBe KEYCLOAK_SESSION
                verify(exactly = 0) { fixture.keycloak.requestAccountToken(any(), any(), any(), null) }
            }
        }
    }

    given("a login after a step-up whose session Keycloak no longer continues") {
        val fixture = KeycloakTokenFixture(afterStepUp(window = TEST_NOW.plusSeconds(600)))
        every { fixture.keycloak.requestAccountToken(AccountId(5L), any(), any(), KEYCLOAK_SESSION) } throws badRequest("invalid_grant")

        `when`("a token is requested") {
            val result = runCatching { fixture.tokenFor() }

            then("the login ends") {
                shouldThrow<SessionExpiredException> { result.getOrThrow() }
                verify(exactly = 0) { fixture.keycloak.requestAccountToken(any(), any(), any(), null) }
            }
        }
    }

    given("a login after a step-up whose session window has lapsed") {
        val fixture = KeycloakTokenFixture(afterStepUp(window = TEST_NOW.minusSeconds(1)))

        `when`("a token is requested") {
            val result = runCatching { fixture.tokenFor() }

            then("the login ends without asking Keycloak") {
                shouldThrow<SessionExpiredException> { result.getOrThrow() }
                verify(exactly = 0) { fixture.keycloak.requestAccountToken(any(), any(), any(), any()) }
            }
        }
    }
})

/** The provider over [session]; Keycloak is a strict mock, so any unplanned grant call fails. */
private class KeycloakTokenFixture(val session: AppTokenSession) {
    private val appTokenSessionId = UUID.randomUUID()
    private val appTokenSessionRepository = mockk<AppTokenSessionRepository> {
        every { findById(appTokenSessionId) } returns Optional.of(session)
        every { save(any()) } answers { firstArg() }
    }
    val keycloak = mockk<KeycloakAdminClient>()
    val sessionEvidenceService = mockk<SessionEvidenceService> { every { getSessionEvidence(any()) } returns null }
    val authPolicy = mockk<AuthPolicy>(relaxed = true)
    val accountService = mockk<AccountService>(relaxed = true)
    private val provider = KeycloakTokenProvider(appTokenSessionRepository, keycloak, sessionEvidenceService, authPolicy, accountService, plainTokenVault, clock = TEST_CLOCK)

    private val appChannel = ChannelSession(channel = ChannelType.APP, now = TEST_NOW).also { it.appTokenSessionId = appTokenSessionId }

    fun tokenFor(minValiditySeconds: Long = TokenService.DEFAULT_MIN_VALIDITY_SECONDS) = provider.tokenFor(appChannel, minValiditySeconds)
}

/** An expired AccessToken of an open Keycloak session, renewable until [refreshExpiresAt]. */
private fun expiredWithRefreshUntil(refreshExpiresAt: Instant) =
    storedAppTokenSession(accountId = AccountId(3L), keycloakSessionId = KEYCLOAK_SESSION, now = TEST_NOW).apply {
        accessToken = "stale"; accessExpiresAt = TEST_NOW.minusSeconds(5)
        refreshToken = EXISTING_REFRESH; this.refreshExpiresAt = refreshExpiresAt
    }

/** What a step-up leaves behind: no cached tokens, but the Keycloak session and its [window]. */
private fun afterStepUp(window: Instant) =
    storedAppTokenSession(accountId = AccountId(5L), keycloakSessionId = KEYCLOAK_SESSION, now = TEST_NOW).apply { refreshExpiresAt = window }

/** A Keycloak-shaped access token: signed, carrying the session id as `sid`. */
private fun keycloakToken(sid: String): String = SignedJWT(
    JWSHeader(JWSAlgorithm.HS256),
    JWTClaimsSet.Builder().claim("sid", sid).build()
).apply { sign(MACSigner(ByteArray(32))) }.serialize()

private fun badRequest(error: String) =
    HttpClientErrorException.create(HttpStatus.BAD_REQUEST, error, HttpHeaders.EMPTY, ByteArray(0), null)
