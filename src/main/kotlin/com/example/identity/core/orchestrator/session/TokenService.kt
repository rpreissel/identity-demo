package com.example.identity.core.orchestrator.session

import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.claims.AttributeType
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID

data class TokenPair(
    val accessToken: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant
)

/**
 * Mock token issuance for the default profile. The AccessToken is an unsecured JWT (alg=none), so
 * the frontend can show its claims without a key. The RefreshToken is a server-side secret; only
 * its expiry is returned. Claims come from the evidence behind [AppTokenSession.sessionEvidenceId].
 */
@Service
// SessionExpiredException is an answer, not a failure: the caller ends the channel in the same
// transaction, which must still commit (ChannelService.getToken).
@Transactional(noRollbackFor = [SessionExpiredException::class])
class TokenService(
    private val appTokenSessionRepository: AppTokenSessionRepository,
    private val sessionEvidenceService: SessionEvidenceService,
    private val authPolicy: AuthPolicy,
    private val accountService: AccountService,
    private val personDirectory: PersonDirectory,
    private val clock: Clock
) {

    /**
     * First issuance and refresh alike. A token with at least [minValiditySeconds] left comes back
     * unchanged; otherwise a new one is minted, which also slides the session window
     * ([REFRESH_TTL] idle timeout). Like Keycloak, only the very first call opens a session
     * ([AppTokenSession.keycloakSessionId]); once its window has lapsed the login ends with
     * [SessionExpiredException], also after a step-up (ADR-43).
     */
    fun tokenFor(appTokenSessionId: UUID, minValiditySeconds: Long = DEFAULT_MIN_VALIDITY_SECONDS): TokenPair {
        val appTokenSession = checkNotNull(appTokenSessionRepository.findByIdOrNull(appTokenSessionId)) {
            "AppTokenSession not found: $appTokenSessionId"
        }
        val now = clock.instant()

        val currentExpiry = appTokenSession.accessExpiresAt
        if (appTokenSession.accessToken != null && currentExpiry != null &&
            currentExpiry.isAfter(now.plusSeconds(minValiditySeconds))
        ) {
            return TokenPair(appTokenSession.accessToken!!, currentExpiry, appTokenSession.refreshExpiresAt!!)
        }

        if (appTokenSession.keycloakSessionId == null) {
            appTokenSession.keycloakSessionId = "$MOCK_SESSION_PREFIX${UUID.randomUUID()}"
        } else if (appTokenSession.refreshExpiresAt?.isAfter(now) != true) {
            throw SessionExpiredException("Session window of AppTokenSession $appTokenSessionId has lapsed")
        }
        if (appTokenSession.refreshToken == null) appTokenSession.refreshToken = "mockrt_${UUID.randomUUID()}"
        // Sliding: every refresh moves the window on, so it lapses only after REFRESH_TTL of idleness.
        appTokenSession.refreshExpiresAt = now.plus(REFRESH_TTL)

        val accessExpiresAt = now.plus(ACCESS_TTL)
        appTokenSession.accessToken = mintAccessToken(appTokenSession, now, accessExpiresAt)
        appTokenSession.accessExpiresAt = accessExpiresAt
        appTokenSessionRepository.save(appTokenSession)

        return TokenPair(appTokenSession.accessToken!!, accessExpiresAt, appTokenSession.refreshExpiresAt!!)
    }

    /** The business ID-token claims, a JSON shape separate from the AccessToken. */
    fun idClaims(appTokenSessionId: UUID): Map<String, Any?> {
        val appTokenSession = checkNotNull(appTokenSessionRepository.findByIdOrNull(appTokenSessionId)) {
            "AppTokenSession not found: $appTokenSessionId"
        }
        val account = appTokenSession.accountId?.let { accountService.findAccount(it) }
        val evidence = evidenceFor(appTokenSession)
        return mapOf(
            "sub" to appTokenSession.accountId?.toString(),
            "acr" to evidence?.let { authPolicy.resolveAcr(it, account) }?.value,
            "amr" to (evidence?.amr?.map { m -> m.value } ?: emptyList()),
            "auth_time" to appTokenSession.authTime?.epochSecond,
            "accountId" to appTokenSession.accountId?.value,
            "personId" to account?.personId?.value,
            // With personId this gives the account's role (ADR-34). Read live: the
            // Personenverzeichnis is its authority.
            "versnr" to account?.personId?.let(personDirectory::memberNumberOf),
            "name" to displayName(account),
            "email" to account?.email,
            "email_verified" to (account?.emailConfirmed ?: false)
        )
    }

    /**
     * The register person's display name when bound, else the account's attested names (ADR-18).
     * `null` for an account with neither.
     */
    private fun displayName(account: AccountProfile?): String? {
        account ?: return null
        account.personId?.let { return personDirectory.displayName(it) }
        val attested = accountService.establishedClaimValues(account.accountId, setOf(AttributeType.FAMILY_NAME, AttributeType.GIVEN_NAMES))
        return listOfNotNull(attested[AttributeType.GIVEN_NAMES], attested[AttributeType.FAMILY_NAME])
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
    }

    /** The evidence of this token session's [SessionEvidenceRecord], or `null` if none was recorded. */
    private fun evidenceFor(appTokenSession: AppTokenSession): SessionEvidence? =
        appTokenSession.sessionEvidenceId?.let { sessionEvidenceService.getSessionEvidence(it) }?.toCoreEvidence()

    private fun mintAccessToken(appTokenSession: AppTokenSession, iat: Instant, exp: Instant): String {
        val account = appTokenSession.accountId?.let { accountService.findAccount(it) }
        val evidence = evidenceFor(appTokenSession)
        val claims = JWTClaimsSet.Builder()
            .subject(appTokenSession.accountId?.toString())
            .issuer(MOCK_ISSUER)
            .audience(MOCK_AUDIENCE)
            .claim("acr", evidence?.let { authPolicy.resolveAcr(it, account) }?.value)
            .claim("amr", evidence?.amr?.map { it.value } ?: emptyList<String>())
            .issueTime(Date.from(iat))
            .expirationTime(Date.from(exp))
            .jwtID(UUID.randomUUID().toString())
            .build()
        return PlainJWT(claims).serialize()
    }

    companion object {
        const val DEFAULT_MIN_VALIDITY_SECONDS: Long = 15
        private val ACCESS_TTL: Duration = Duration.ofMinutes(5)
        private val REFRESH_TTL: Duration = Duration.ofMinutes(30)
        private const val MOCK_ISSUER = "mock-keycloak"
        const val MOCK_SESSION_PREFIX = "mock-session-"
        private const val MOCK_AUDIENCE = "identity-demo-orchestrator"
    }
}
