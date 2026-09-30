package com.example.identity.core.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AppTokenSessionRepository : JpaRepository<AppTokenSession, UUID> {
    fun findByAccountId(accountId: Long): List<AppTokenSession>

    /** Every [AppTokenSession] minted from this session evidence, whose cached tokens go stale when it changes. */
    fun findBySessionEvidenceId(sessionEvidenceId: UUID): List<AppTokenSession>

    fun findByKeycloakSessionId(keycloakSessionId: String): List<AppTokenSession>

    fun findByKeycloakSessionIdIn(keycloakSessionIds: Collection<String>): List<AppTokenSession>
}
