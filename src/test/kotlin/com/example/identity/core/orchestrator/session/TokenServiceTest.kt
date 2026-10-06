package com.example.identity.core.orchestrator.session

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.Subject
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.AmrSource
import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.nimbusds.jwt.PlainJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.Optional
import java.util.UUID

/** What the mock tokens name as issuer and audience; the Keycloak profile's tokens carry the same. */
private const val MOCK_ISSUER = "mock-keycloak"
private const val MOCK_AUDIENCE = "identity-demo-orchestrator"

/**
 * Unit test of [TokenService]'s mock token issuance: the three branches of `tokenFor` (still
 * valid, silent refresh, full re-issuance) and the AccessToken's JWT shape. `acr` is stubbed to a
 * fixed "loa2"; the combination logic belongs to `DefaultAuthPolicyTest`.
 */
class TokenServiceTest : BehaviorSpec({

    given("an AccessToken that still has well over minValiditySeconds left") {
        val fixture = TokenServiceFixture(appTokenSession(
            accessToken = "existing-token", accessExpiresAt = TEST_NOW.plusSeconds(300), refreshExpiresAt = TEST_NOW.plusSeconds(1800)
        ))

        `when`("a token is requested with 15 seconds minimum validity") {
            val result = fixture.service.tokenFor(fixture.appTokenSessionId, minValiditySeconds = 15)

            then("it is returned unchanged - repeatable reads stay idempotent, nothing is minted or saved") {
                result.accessToken shouldBe "existing-token"
                result.accessExpiresAt shouldBe fixture.session.accessExpiresAt
                verify(exactly = 0) { fixture.repository.save(any()) }
            }
        }
    }

    given("an AccessToken about to expire within minValiditySeconds, but the RefreshToken is still valid") {
        val refreshHandle = "mockrt_original"
        val refreshExpiry = TEST_NOW.plusSeconds(1000)
        val fixture = TokenServiceFixture(appTokenSession(
            accessToken = "stale-token", accessExpiresAt = TEST_NOW.plusSeconds(5),
            refreshToken = refreshHandle, refreshExpiresAt = refreshExpiry
        ))

        `when`("a token is requested with 15 seconds minimum validity") {
            val result = fixture.service.tokenFor(fixture.appTokenSessionId, minValiditySeconds = 15)

            then("a new AccessToken is minted with the same RefreshToken, whose window moves on (idle timeout)") {
                result.accessToken shouldNotBe "stale-token"
                fixture.session.refreshToken shouldBe refreshHandle
                result.refreshExpiresAt.isAfter(refreshExpiry) shouldBe true
                verify(exactly = 1) { fixture.repository.save(fixture.session) }
            }
        }
    }

    given("both the AccessToken and RefreshToken have expired") {
        val refreshHandle = "mockrt_expired"
        val fixture = TokenServiceFixture(appTokenSession(
            accessToken = "stale-token", accessExpiresAt = TEST_NOW.minusSeconds(5),
            refreshToken = refreshHandle, refreshExpiresAt = TEST_NOW.minusSeconds(5)
        ))

        `when`("a token is requested") {
            val result = runCatching { fixture.service.tokenFor(fixture.appTokenSessionId) }

            then("the login is over - nothing is re-issued, not even a fresh pair") {
                shouldThrow<SessionExpiredException> { result.getOrThrow() }
                fixture.session.refreshToken shouldBe refreshHandle
                verify(exactly = 0) { fixture.repository.save(any()) }
            }
        }
    }

    given("the first token of a login - no RefreshToken yet") {
        val fixture = TokenServiceFixture(appTokenSession())

        `when`("a token is requested") {
            val result = fixture.service.tokenFor(fixture.appTokenSessionId)

            then("both are issued, and the login's one session is opened") {
                fixture.session.refreshToken.shouldNotBeNull()
                fixture.session.keycloakSessionId!! shouldStartWith TokenService.MOCK_SESSION_PREFIX
                result.refreshExpiresAt.isAfter(TEST_NOW) shouldBe true
            }
        }
    }

    // A step-up cleared the cached tokens of an open session (ADR-43).
    given("a login after a step-up, its session window still open") {
        val fixture = TokenServiceFixture(appTokenSession(refreshExpiresAt = TEST_NOW.plusSeconds(600)))
        val sessionBefore = fixture.session.keycloakSessionId

        `when`("a token is requested") {
            fixture.service.tokenFor(fixture.appTokenSessionId)

            then("the token continues the same session instead of opening another") {
                fixture.session.keycloakSessionId shouldBe sessionBefore
                fixture.session.accessToken.shouldNotBeNull()
            }
        }
    }

    given("a login after a step-up whose session window has lapsed") {
        val fixture = TokenServiceFixture(appTokenSession(refreshExpiresAt = TEST_NOW.minusSeconds(1)))

        `when`("a token is requested") {
            val result = runCatching { fixture.service.tokenFor(fixture.appTokenSessionId) }

            then("the login ends: no new session, no token") {
                shouldThrow<SessionExpiredException> { result.getOrThrow() }
                fixture.session.accessToken.shouldBeNull()
                verify(exactly = 0) { fixture.repository.save(any()) }
            }
        }
    }

    given("no AppTokenSession under the given id") {
        val fixture = TokenServiceFixture(session = null)

        `when`("a token is requested") {
            val result = runCatching { fixture.service.tokenFor(fixture.appTokenSessionId) }

            then("it fails loudly rather than minting a token for nothing") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }

    given("a login of account 99 with sms and password evidence") {
        val fixture = TokenServiceFixture(appTokenSession(accountId = AccountId(99L)))

        `when`("a fresh AccessToken is minted") {
            val result = fixture.service.tokenFor(fixture.appTokenSessionId)

            then("it is a spec-shaped unsecured JWT carrying the session's own acr/amr, parseable without a key") {
                val claims = PlainJWT.parse(result.accessToken).jwtClaimsSet
                claims.subject shouldBe "99"
                claims.getStringClaim("acr") shouldBe "loa2"
                claims.getStringListClaim("amr") shouldBe listOf("sms", "password")
                claims.issuer shouldBe MOCK_ISSUER
                claims.audience shouldBe listOf(MOCK_AUDIENCE)
                // `exp` is a NumericDate in whole seconds (RFC 7519 #2), so compare at that granularity.
                claims.expirationTime.toInstant().epochSecond shouldBe result.accessExpiresAt.epochSecond
            }
        }
    }

    given("a login of an account bound to a register person") {
        val fixture = TokenServiceFixture(appTokenSession(accountId = AccountId(7L)).apply { authTime = TEST_NOW })
        every { fixture.accountService.findAccount(AccountId(7L)) } returns AccountProfile(
            accountId = AccountId(7L), personId = PartnerNumber("P000000055"), authenticationMethods = emptyList(),
            email = "max@example.test", emailConfirmedAt = TEST_NOW
        )

        `when`("resolving the ID-token claims") {
            val claims = fixture.service.idClaims(fixture.appTokenSessionId)

            then("the fachliche claim set carries account and person identifiers, not just the raw session state") {
                claims["sub"] shouldBe "7"
                claims["personId"] shouldBe "P000000055"
                claims["email"] shouldBe "max@example.test"
                claims["email_verified"] shouldBe true
            }
        }
    }

    // ADR-18: a full-attested Interessent has no register person.
    given("a login of an Interessent with attested name claims") {
        val fixture = interessent(mapOf(AttributeType.GIVEN_NAMES to "Erika", AttributeType.FAMILY_NAME to "Musterfrau"))

        `when`("resolving the ID-token claims") {
            val claims = fixture.service.idClaims(fixture.appTokenSessionId)

            then("the name falls back to the account's own attested claims, personId stays absent") {
                claims["personId"] shouldBe null
                claims["name"] shouldBe "Erika Musterfrau"
                verify(exactly = 0) { fixture.personDirectory.displayName(any()) }
            }
        }
    }

    given("a login of an Interessent without any attested name claims") {
        val fixture = interessent(emptyMap())

        `when`("resolving the ID-token claims") {
            val claims = fixture.service.idClaims(fixture.appTokenSessionId)

            then("the name is null - not a placeholder") {
                claims["name"] shouldBe null
            }
        }
    }
})

/**
 * The service over [session], found under [appTokenSessionId], whose evidence holds sms and password
 * at loa1 for the session's account. The policy rates any evidence loa2.
 */
private class TokenServiceFixture(session: AppTokenSession?) {
    val appTokenSessionId: UUID = UUID.randomUUID()
    val session: AppTokenSession = session ?: AppTokenSession(accountId = AccountId(0L), now = TEST_NOW)
    val repository = mockk<AppTokenSessionRepository> {
        every { findById(appTokenSessionId) } returns Optional.ofNullable(session)
        every { save(any()) } answers { firstArg() }
    }
    private val sessionEvidenceService = mockk<SessionEvidenceService> {
        every { getSessionEvidence(any()) } returns null
        session?.sessionEvidenceId?.let { id ->
            every { getSessionEvidence(id) } returns smsAndPasswordEvidence(checkNotNull(session.accountId))
        }
    }
    private val authPolicy = mockk<AuthPolicy> { every { resolveAcr(any(), any()) } returns AcrLevel.LOA2 }
    val accountService = mockk<AccountService>(relaxed = true)
    val personDirectory = mockk<PersonDirectory>(relaxed = true)
    val service = TokenService(repository, sessionEvidenceService, authPolicy, accountService, personDirectory, plainTokenVault, clock = TEST_CLOCK)
}

private fun appTokenSession(
    accountId: AccountId = AccountId(42L),
    accessToken: String? = null,
    accessExpiresAt: Instant? = null,
    refreshToken: String? = null,
    refreshExpiresAt: Instant? = null,
) = AppTokenSession(
    accountId = accountId,
    // A context with a refresh window belongs to a login whose session is open.
    keycloakSessionId = refreshExpiresAt?.let { "${TokenService.MOCK_SESSION_PREFIX}test" },
    now = TEST_NOW
).apply {
    this.accessToken = accessToken
    this.accessExpiresAt = accessExpiresAt
    this.refreshToken = refreshToken
    this.refreshExpiresAt = refreshExpiresAt
    this.sessionEvidenceId = SessionEvidenceId(UUID.randomUUID())
}

private fun smsAndPasswordEvidence(accountId: AccountId) = SessionEvidenceRecord(Subject.Account(accountId), TEST_NOW).apply {
    addAmr(
        listOf(
            MethodEvidence(MethodName("sms"), AcrLevel.LOA1, amrSourceId = "auth-sms", source = AmrSource.ORCHESTRATOR),
            MethodEvidence(MethodName("password"), AcrLevel.LOA1, amrSourceId = "auth-password", source = AmrSource.ORCHESTRATOR),
        ),
        now = TEST_NOW
    )
}

/** A login of account 8, which has no register person but [attested] name claims of its own. */
private fun interessent(attested: Map<AttributeType, String>) =
    TokenServiceFixture(appTokenSession(accountId = AccountId(8L)).apply { authTime = TEST_NOW }).apply {
        every { accountService.findAccount(AccountId(8L)) } returns AccountProfile(
            accountId = AccountId(8L), personId = null, authenticationMethods = emptyList(),
            email = "erika@example.test", emailConfirmedAt = TEST_NOW
        )
        every { accountService.establishedClaimValues(AccountId(8L), any()) } returns attested
    }
