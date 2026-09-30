package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

/** App-channel token bookkeeping, see [AppTokenSession]. */
@Service
@Transactional
class AppTokenSessionService(
    private val appTokenSessionRepository: AppTokenSessionRepository,
    private val clock: Clock
) {

    fun createForAccount(accountId: AccountId, sessionEvidenceId: SessionEvidenceId): AppTokenSession =
        appTokenSessionRepository.save(AppTokenSession(accountId = accountId, now = clock.instant()).apply { this.sessionEvidenceId = sessionEvidenceId })

    fun save(appTokenSession: AppTokenSession): AppTokenSession = appTokenSessionRepository.save(appTokenSession)

    fun getAppTokenSession(appTokenSessionId: UUID): AppTokenSession? =
        appTokenSessionRepository.findByIdOrNull(appTokenSessionId)

    /** The App login that holds Keycloak session [keycloakSessionId], as a list: the column is not unique. */
    fun findByKeycloakSessionId(keycloakSessionId: String): List<AppTokenSession> =
        appTokenSessionRepository.findByKeycloakSessionId(keycloakSessionId)
}
