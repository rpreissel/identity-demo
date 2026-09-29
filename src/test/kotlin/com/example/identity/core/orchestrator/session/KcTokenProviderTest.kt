package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.Subject
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import org.springframework.web.client.HttpClientErrorException
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import io.kotest.assertions.throwables.shouldThrow
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.account.AccountService
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.kc.AccountTokenResponse
import com.example.identity.core.orchestrator.kc.KeycloakAdminClient
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import io.kotest.core.spec.style.BehaviorSpec
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Unit test of [KcTokenProvider], the `keycloak`-profile [TokenProvider]: the still-valid short-circuit
 * matches [TokenService]'s, a token with unchanged evidence is renewed via Keycloak's `refresh_token`
 * grant, and a fresh mint sends the current acr/amr to the account-token grant (ADR-9, addendum F-6).
 */
class KcTokenProviderTest : BehaviorSpec({

    fun provider(
        authContextRepository: AuthContextRepository,
        keycloakAdminClient: KeycloakAdminClient = mockk(),
        authEvidenceService: AuthEvidenceService = mockk { every { getAuthEvidence(any()) } returns null },
        authPolicy: AuthPolicy = mockk(relaxed = true),
        accountService: AccountService = mockk(relaxed = true)
    ) = KcTokenProvider(authContextRepository, keycloakAdminClient, authEvidenceService, authPolicy, accountService, clock = TEST_CLOCK)

    fun appChannel(authContextId: UUID) = ChannelSession(channel = ChannelType.APP, now = TEST_NOW).apply {
        this.authContextId = authContextId
    }

    /** A Keycloak-shaped access token: signed, carrying the session id as `sid`. */
    fun keycloakToken(sid: String): String = SignedJWT(
        JWSHeader(JWSAlgorithm.HS256),
        JWTClaimsSet.Builder().claim("sid", sid).build()
    ).apply { sign(MACSigner(ByteArray(32))) }.serialize()

    given("an AccessToken that still has well over minValiditySeconds left") {
        then("it is returned unchanged - no grant call") {
            val authContextId = UUID.randomUUID()
            val expiry = TEST_NOW.plusSeconds(300)
            val ctx = AuthContext(accountId = 42L, now = TEST_NOW).apply { accessToken = "existing-token"; accessExpiresAt = expiry }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findById(authContextId) } returns Optional.of(ctx)
            val keycloakAdminClient = mockk<KeycloakAdminClient>()

            val result = provider(authContextRepository, keycloakAdminClient = keycloakAdminClient)
                .tokenFor(appChannel(authContextId), minValiditySeconds = 15)

            result.accessToken shouldBe "existing-token"
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any(), any()) }
            verify(exactly = 0) { keycloakAdminClient.refreshAccountToken(any()) }
        }
    }

    given("an expiring AccessToken whose RefreshToken is still valid (no step-up in between)") {
        then("renews via Keycloak's own refresh_token grant") {
            val authContextId = UUID.randomUUID()
            val accountId = 3L
            val ctx = AuthContext(accountId = accountId, keycloakSessionId = "kc-session", now = TEST_NOW).apply {
                accessToken = "stale"; accessExpiresAt = TEST_NOW.minusSeconds(5)
                refreshToken = "existing-refresh"; refreshExpiresAt = TEST_NOW.plusSeconds(600)
            }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findById(authContextId) } returns Optional.of(ctx)
            every { authContextRepository.save(any()) } answers { firstArg() }
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            every { keycloakAdminClient.refreshAccountToken("existing-refresh") } returns
                AccountTokenResponse("renewed-access-token", 300, "rotated-refresh", 600)

            val result = provider(authContextRepository, keycloakAdminClient).tokenFor(appChannel(authContextId))

            result.accessToken shouldBe "renewed-access-token"
            ctx.refreshToken shouldBe "rotated-refresh"
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any(), any()) }
        }
    }

    given("a RefreshToken whose window has lapsed, or that Keycloak refuses") {
        fun contextWith(refreshExpiresAt: Instant) = AuthContext(accountId = 3L, keycloakSessionId = "kc-session", now = TEST_NOW).apply {
            accessToken = "stale"; accessExpiresAt = TEST_NOW.minusSeconds(5)
            refreshToken = "existing-refresh"; this.refreshExpiresAt = refreshExpiresAt
        }
        fun repositoryWith(id: UUID, ctx: AuthContext) = mockk<AuthContextRepository>().also {
            every { it.findById(id) } returns Optional.of(ctx)
            every { it.save(any()) } answers { firstArg() }
        }

        then("a lapsed window ends the login - no new Keycloak session is opened behind Keycloak's back") {
            val id = UUID.randomUUID()
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            shouldThrow<SessionExpiredException> {
                provider(repositoryWith(id, contextWith(TEST_NOW.minusSeconds(1))), keycloakAdminClient).tokenFor(appChannel(id))
            }
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any(), any()) }
        }

        then("a refresh Keycloak refuses (its session ended) ends the login too") {
            val id = UUID.randomUUID()
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            every { keycloakAdminClient.refreshAccountToken("existing-refresh") } throws
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "invalid_grant", HttpHeaders.EMPTY, ByteArray(0), null)
            shouldThrow<SessionExpiredException> {
                provider(repositoryWith(id, contextWith(TEST_NOW.plusSeconds(600))), keycloakAdminClient).tokenFor(appChannel(id))
            }
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any(), any()) }
        }
    }

    given("the first token of a login") {
        then("the account-token grant opens a session and the token carries the current acr/amr") {
            val authContextId = UUID.randomUUID()
            val accountId = 7L
            val authEvidenceId = UUID.randomUUID()
            val ctx = AuthContext(accountId = accountId, now = TEST_NOW).apply {
                accessToken = "stale"; accessExpiresAt = TEST_NOW.minusSeconds(5)
                this.authEvidenceId = authEvidenceId
            }
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findById(authContextId) } returns Optional.of(ctx)
            every { authContextRepository.save(any()) } answers { firstArg() }
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            val token = keycloakToken(sid = "kc-session-7")
            every { keycloakAdminClient.requestAccountToken(accountId, "loa2", any(), null) } returns
                AccountTokenResponse(token, 300, "fresh-refresh", 600)
            val authPolicy = mockk<AuthPolicy> { every { resolveAcr(any(), any()) } returns AcrLevel.LOA2 }
            val authEvidenceService = mockk<AuthEvidenceService> {
                every { getAuthEvidence(authEvidenceId) } returns EvidenceTrail(Subject.Account(accountId), TEST_NOW)
            }
            val accountService = mockk<AccountService> {
                every { findAccount(accountId) } returns com.example.identity.core.account.AccountProfile(
                    accountId = accountId, personId = "P000000001",
                    authenticationMethods = listOf(
                        com.example.identity.core.account.AuthMethodView(
                            id = "m1", method = "password", active = true,
                            createdAt = TEST_NOW, enrolledUnderAcr = "loa1", details = null,
                            enrollmentRef = com.example.identity.contract.tool_api.EnrollmentRef("auth_password.enrollment", "1")
                        )
                    )
                )
            }

            val result = provider(authContextRepository, keycloakAdminClient, authEvidenceService, authPolicy, accountService)
                .tokenFor(appChannel(authContextId))

            result.accessToken shouldBe token
            ctx.accessToken shouldBe token
            ctx.refreshToken shouldBe "fresh-refresh"
            ctx.keycloakSessionId shouldBe "kc-session-7"
            verify { keycloakAdminClient.requestAccountToken(accountId, "loa2", any(), null) }
        }
    }

    given("a first token that Keycloak refuses to mint (ADR-43)") {
        then("no session was opened: SessionRefusedException, and nothing is cached") {
            val authContextId = UUID.randomUUID()
            val ctx = AuthContext(accountId = 9L, now = TEST_NOW)
            val authContextRepository = mockk<AuthContextRepository>()
            every { authContextRepository.findById(authContextId) } returns Optional.of(ctx)
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            every { keycloakAdminClient.requestAccountToken(9L, any(), any(), null) } throws
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "invalid_request", HttpHeaders.EMPTY, ByteArray(0), null)

            shouldThrow<SessionRefusedException> {
                provider(authContextRepository, keycloakAdminClient).tokenFor(appChannel(authContextId))
            }
            ctx.accessToken shouldBe null
            ctx.refreshToken shouldBe null
        }
    }

    given("a step-up cleared the cached tokens of a login whose session is open (ADR-43)") {
        fun afterStepUp(window: Instant) = AuthContext(accountId = 5L, keycloakSessionId = "kc-session-5", now = TEST_NOW).apply {
            refreshExpiresAt = window
        }
        fun repositoryWith(id: UUID, ctx: AuthContext) = mockk<AuthContextRepository>().also {
            every { it.findById(id) } returns Optional.of(ctx)
            every { it.save(any()) } answers { firstArg() }
        }

        then("the grant continues exactly that session - it never opens a second one") {
            val id = UUID.randomUUID()
            val ctx = afterStepUp(TEST_NOW.plusSeconds(600))
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            every { keycloakAdminClient.requestAccountToken(5L, any(), any(), "kc-session-5") } returns
                AccountTokenResponse(keycloakToken(sid = "kc-session-5"), 300, "fresh-refresh", 500)

            provider(repositoryWith(id, ctx), keycloakAdminClient).tokenFor(appChannel(id))

            ctx.keycloakSessionId shouldBe "kc-session-5"
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any(), null) }
        }

        then("a session Keycloak no longer continues ends the login") {
            val id = UUID.randomUUID()
            val keycloakAdminClient = mockk<KeycloakAdminClient>()
            every { keycloakAdminClient.requestAccountToken(5L, any(), any(), "kc-session-5") } throws
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "invalid_grant", HttpHeaders.EMPTY, ByteArray(0), null)

            shouldThrow<SessionExpiredException> {
                provider(repositoryWith(id, afterStepUp(TEST_NOW.plusSeconds(600))), keycloakAdminClient).tokenFor(appChannel(id))
            }
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any(), null) }
        }

        then("a lapsed window ends the login without asking Keycloak") {
            val id = UUID.randomUUID()
            val keycloakAdminClient = mockk<KeycloakAdminClient>()

            shouldThrow<SessionExpiredException> {
                provider(repositoryWith(id, afterStepUp(TEST_NOW.minusSeconds(1))), keycloakAdminClient).tokenFor(appChannel(id))
            }
            verify(exactly = 0) { keycloakAdminClient.requestAccountToken(any(), any(), any(), any()) }
        }
    }
})
