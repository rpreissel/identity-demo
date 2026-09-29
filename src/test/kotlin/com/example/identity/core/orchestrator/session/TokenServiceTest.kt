package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.Subject
import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import io.kotest.assertions.throwables.shouldThrow
import com.example.identity.core.account.AccountService
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.nimbusds.jwt.PlainJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import java.util.UUID
import com.example.identity.core.orchestrator.domain.AmrSource

/**
 * Unit test of [TokenService]'s mock token issuance: the three branches of `tokenFor` (still
 * valid, silent refresh, full re-issuance) and the AccessToken's JWT shape. `acr` is stubbed to a
 * fixed "loa2"; the combination logic belongs to `DefaultAuthPolicyTest`.
 */
class TokenServiceTest : BehaviorSpec({

    fun authContext(
        accountId: Long? = 42L,
        accessToken: String? = null,
        accessExpiresAt: Instant? = null,
        refreshToken: String? = null,
        refreshExpiresAt: Instant? = null,
        authEvidenceId: UUID? = UUID.randomUUID(),
        // A context with tokens belongs to a login whose session is open.
        keycloakSessionId: String? = if (refreshExpiresAt != null) "${TokenService.MOCK_SESSION_PREFIX}test" else null
    ) = AuthContext(accountId = accountId, keycloakSessionId = keycloakSessionId, now = TEST_NOW).apply {
        this.accessToken = accessToken
        this.accessExpiresAt = accessExpiresAt
        this.refreshToken = refreshToken
        this.refreshExpiresAt = refreshExpiresAt
        this.authEvidenceId = authEvidenceId
    }

    fun evidence(accountId: Long = 42L) = EvidenceTrail(Subject.Account(accountId), TEST_NOW).apply {
        addAmr(
            listOf(
                MethodEvidence(MethodName("sms"), AcrLevel.LOA1, amrSourceId = "auth-sms", source = AmrSource.ORCHESTRATOR),
                MethodEvidence(MethodName("password"), AcrLevel.LOA1, amrSourceId = "auth-password", source = AmrSource.ORCHESTRATOR),
            ),
            now = TEST_NOW
        )
    }

    fun evidenceService(authEvidenceId: UUID?, forAccount: EvidenceTrail?): AuthEvidenceService {
        val service = mockk<AuthEvidenceService>()
        every { service.getAuthEvidence(any()) } returns null
        if (authEvidenceId != null) every { service.getAuthEvidence(authEvidenceId) } returns forAccount
        return service
    }

    fun policy(acr: AcrLevel = AcrLevel.LOA2): AuthPolicy {
        val policy = mockk<AuthPolicy>()
        every { policy.resolveAcr(any(), any()) } returns acr
        return policy
    }

    fun service(
        repository: AuthContextRepository,
        authEvidenceService: AuthEvidenceService = mockk(relaxed = true),
        authPolicy: AuthPolicy = policy(),
        accountService: AccountService = mockk(relaxed = true),
        personDirectory: PersonDirectory = mockk(relaxed = true)
    ) = TokenService(repository, authEvidenceService, authPolicy, accountService, personDirectory, clock = TEST_CLOCK)

    given("an AccessToken that still has well over minValiditySeconds left") {
        val authContextId = UUID.randomUUID()
        val ctx = authContext(accessToken = "existing-token", accessExpiresAt = TEST_NOW.plusSeconds(300), refreshExpiresAt = TEST_NOW.plusSeconds(1800))
        val repository = mockk<AuthContextRepository>()
        every { repository.findById(authContextId) } returns Optional.of(ctx)

        then("it is returned unchanged - repeatable reads stay idempotent, nothing is minted or saved") {
            val result = service(repository).tokenFor(authContextId, minValiditySeconds = 15)

            result.accessToken shouldBe "existing-token"
            result.accessExpiresAt shouldBe ctx.accessExpiresAt
            verify(exactly = 0) { repository.save(any()) }
        }
    }

    given("an AccessToken about to expire within minValiditySeconds, but the RefreshToken is still valid") {
        val authContextId = UUID.randomUUID()
        val originalRefreshHandle = "mockrt_original"
        val originalRefreshExpiry = TEST_NOW.plusSeconds(1000)
        val ctx = authContext(
            accessToken = "stale-token", accessExpiresAt = TEST_NOW.plusSeconds(5),
            refreshToken = originalRefreshHandle, refreshExpiresAt = originalRefreshExpiry
        )
        val repository = mockk<AuthContextRepository>()
        every { repository.findById(authContextId) } returns Optional.of(ctx)
        every { repository.save(any()) } answers { firstArg() }

        then("a new AccessToken is minted with the same RefreshToken, whose window moves on (idle timeout)") {
            val result = service(repository, evidenceService(ctx.authEvidenceId, evidence())).tokenFor(authContextId, minValiditySeconds = 15)

            result.accessToken shouldNotBe "stale-token"
            ctx.refreshToken shouldBe originalRefreshHandle
            result.refreshExpiresAt.isAfter(originalRefreshExpiry) shouldBe true
            verify(exactly = 1) { repository.save(ctx) }
        }
    }

    given("both the AccessToken and RefreshToken have expired") {
        val authContextId = UUID.randomUUID()
        val oldRefreshHandle = "mockrt_expired"
        val ctx = authContext(
            accessToken = "stale-token", accessExpiresAt = TEST_NOW.minusSeconds(5),
            refreshToken = oldRefreshHandle, refreshExpiresAt = TEST_NOW.minusSeconds(5)
        )
        val repository = mockk<AuthContextRepository>()
        every { repository.findById(authContextId) } returns Optional.of(ctx)
        every { repository.save(any()) } answers { firstArg() }

        then("the login is over - nothing is re-issued, not even a fresh pair") {
            shouldThrow<SessionExpiredException> {
                service(repository, evidenceService(ctx.authEvidenceId, evidence())).tokenFor(authContextId)
            }
            ctx.refreshToken shouldBe oldRefreshHandle
            verify(exactly = 0) { repository.save(any()) }
        }
    }

    given("the first token of a login - no RefreshToken yet") {
        val authContextId = UUID.randomUUID()
        val ctx = authContext(accessToken = null, accessExpiresAt = null, refreshToken = null, refreshExpiresAt = null)
        val repository = mockk<AuthContextRepository>()
        every { repository.findById(authContextId) } returns Optional.of(ctx)
        every { repository.save(any()) } answers { firstArg() }

        then("both are issued, and the login's one session is opened") {
            val result = service(repository, evidenceService(ctx.authEvidenceId, evidence())).tokenFor(authContextId)

            ctx.refreshToken shouldNotBe null
            ctx.keycloakSessionId!!.startsWith(TokenService.MOCK_SESSION_PREFIX) shouldBe true
            result.refreshExpiresAt.isAfter(TEST_NOW) shouldBe true
        }
    }

    given("a step-up cleared the cached tokens of an open session (ADR-43)") {
        fun afterStepUp(window: Instant) = authContext(refreshToken = null, refreshExpiresAt = window)

        then("the next token continues the same session instead of opening another") {
            val authContextId = UUID.randomUUID()
            val ctx = afterStepUp(TEST_NOW.plusSeconds(600))
            val session = ctx.keycloakSessionId
            val repository = mockk<AuthContextRepository>()
            every { repository.findById(authContextId) } returns Optional.of(ctx)
            every { repository.save(any()) } answers { firstArg() }

            service(repository, evidenceService(ctx.authEvidenceId, evidence())).tokenFor(authContextId)

            ctx.keycloakSessionId shouldBe session
            ctx.accessToken shouldNotBe null
        }

        then("a lapsed window ends the login: no new session, no token") {
            val authContextId = UUID.randomUUID()
            val ctx = afterStepUp(TEST_NOW.minusSeconds(1))
            val repository = mockk<AuthContextRepository>()
            every { repository.findById(authContextId) } returns Optional.of(ctx)

            shouldThrow<SessionExpiredException> {
                service(repository, evidenceService(ctx.authEvidenceId, evidence())).tokenFor(authContextId)
            }
            ctx.accessToken shouldBe null
            verify(exactly = 0) { repository.save(any()) }
        }
    }

    given("no AuthContext under the given id") {
        then("it fails loudly rather than minting a token for nothing") {
            val authContextId = UUID.randomUUID()
            val repository = mockk<AuthContextRepository>()
            every { repository.findById(authContextId) } returns Optional.empty()

            io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
                service(repository).tokenFor(authContextId)
            }
        }
    }

    given("minting a fresh AccessToken") {
        val authContextId = UUID.randomUUID()
        val ctx = authContext(accountId = 99L)
        val repository = mockk<AuthContextRepository>()
        every { repository.findById(authContextId) } returns Optional.of(ctx)
        every { repository.save(any()) } answers { firstArg() }

        then("it is a spec-shaped unsecured JWT carrying the session's own acr/amr, parseable without a key") {
            val result = service(repository, evidenceService(ctx.authEvidenceId, evidence(accountId = 99L))).tokenFor(authContextId)

            val claims = PlainJWT.parse(result.accessToken).jwtClaimsSet
            claims.subject shouldBe "99"
            claims.getStringClaim("acr") shouldBe "loa2"
            claims.getStringListClaim("amr") shouldBe listOf("sms", "password")
            claims.issuer shouldBe "mock-keycloak"
            claims.audience shouldBe listOf("identity-demo-orchestrator")
            // `exp` is a NumericDate in whole seconds (RFC 7519 #2), so compare at that granularity.
            claims.expirationTime.toInstant().epochSecond shouldBe result.accessExpiresAt.epochSecond
        }
    }

    given("resolving the ID-token claims for a known account") {
        then("the fachliche claim set carries account and person identifiers, not just the raw session state") {
            val authContextId = UUID.randomUUID()
            val ctx = authContext(accountId = 7L)
            ctx.authTime = TEST_NOW
            val repository = mockk<AuthContextRepository>()
            every { repository.findById(authContextId) } returns Optional.of(ctx)
            val accountService = mockk<AccountService>()
            every { accountService.findAccount(7L) } returns com.example.identity.core.account.AccountProfile(
                accountId = 7L, personId = "P000000055", authenticationMethods = emptyList(),
                email = "max@example.test", emailConfirmedAt = TEST_NOW
            )

            val claims = service(repository, evidenceService(ctx.authEvidenceId, evidence(accountId = 7L)), accountService = accountService).idClaims(authContextId)

            claims["sub"] shouldBe "7"
            claims["personId"] shouldBe "P000000055"
            claims["email"] shouldBe "max@example.test"
            claims["email_verified"] shouldBe true
        }
    }

    given("resolving the ID-token claims for a full-attested Interessent (ADR-18: no register person)") {
        val authContextId = UUID.randomUUID()
        val ctx = authContext(accountId = 8L)
        ctx.authTime = TEST_NOW
        val repository = mockk<AuthContextRepository>()
        every { repository.findById(authContextId) } returns Optional.of(ctx)

        fun accountService(attested: Map<AttributeType, String>): AccountService {
            val accountService = mockk<AccountService>()
            every { accountService.findAccount(8L) } returns com.example.identity.core.account.AccountProfile(
                accountId = 8L, personId = null, authenticationMethods = emptyList(),
                email = "erika@example.test", emailConfirmedAt = TEST_NOW
            )
            every { accountService.establishedClaimValues(8L, any()) } returns attested
            return accountService
        }

        then("the name falls back to the account's own attested claims, personId stays absent") {
            val personDirectory = mockk<PersonDirectory>()

            val claims = service(
                repository,
                evidenceService(ctx.authEvidenceId, evidence(accountId = 8L)),
                accountService = accountService(mapOf(AttributeType.GIVEN_NAMES to "Erika", AttributeType.FAMILY_NAME to "Musterfrau")),
                personDirectory = personDirectory
            ).idClaims(authContextId)

            claims["personId"] shouldBe null
            claims["name"] shouldBe "Erika Musterfrau"
            verify(exactly = 0) { personDirectory.displayName(any()) }
        }

        then("without any attested name claims either, the name is null - not a placeholder") {
            val claims = service(
                repository,
                evidenceService(ctx.authEvidenceId, evidence(accountId = 8L)),
                accountService = accountService(emptyMap())
            ).idClaims(authContextId)

            claims["name"] shouldBe null
        }
    }
})
