package com.example.identity.core.orchestrator.session

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** App-channel token bookkeeping, see [AuthContext]. */
@Service
@Transactional
class AuthContextService(
    private val authContextRepository: AuthContextRepository,
    private val clock: Clock
) {

    fun createForAccount(accountId: Long, authEvidenceId: UUID): AuthContext =
        authContextRepository.save(AuthContext(accountId = accountId, now = clock.instant()).apply { this.authEvidenceId = authEvidenceId })

    fun save(authContext: AuthContext): AuthContext = authContextRepository.save(authContext)

    fun getAuthContext(authContextId: UUID): AuthContext? =
        authContextRepository.findByIdOrNull(authContextId)

    /** The App login that holds Keycloak session [keycloakSessionId], as a list: the column is not unique. */
    fun findByKeycloakSessionId(keycloakSessionId: String): List<AuthContext> =
        authContextRepository.findByKeycloakSessionId(keycloakSessionId)
}
