package com.example.identity.core.orchestrator.session

import com.example.identity.core.account.AccountProfile
import com.example.identity.core.account.AccountService
import com.example.identity.core.orchestrator.domain.policy.SessionEvidence
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.claims.AttributeType
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

data class TokenPair(
    val accessToken: String,
    val accessExpiresAt: Instant,
    val refreshExpiresAt: Instant
)

/**
 * The claims behind the App channel's tokens, read from the evidence behind
 * [AppTokenSession.sessionEvidenceId]. Keycloak issues the tokens themselves ([KeycloakTokenProvider]).
 */
@Service
@Transactional(readOnly = true)
class TokenService(
    private val appTokenSessionRepository: AppTokenSessionRepository,
    private val sessionEvidenceService: SessionEvidenceService,
    private val authPolicy: AuthPolicy,
    private val accountService: AccountService,
    private val personDirectory: PersonDirectory,
) {

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

    companion object {
        const val DEFAULT_MIN_VALIDITY_SECONDS: Long = 15
    }
}
