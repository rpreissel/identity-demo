package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AppTokenSessionRepository : JpaRepository<AppTokenSession, UUID> {
    fun findByAccountId(accountId: AccountId?): List<AppTokenSession>

    /** Every [AppTokenSession] minted from this session evidence, whose cached tokens go stale when it changes. */
    fun findBySessionEvidenceId(sessionEvidenceId: SessionEvidenceId): List<AppTokenSession>

    fun findByKeycloakSessionId(keycloakSessionId: String): List<AppTokenSession>

    fun findByKeycloakSessionIdIn(keycloakSessionIds: Collection<String>): List<AppTokenSession>
}
