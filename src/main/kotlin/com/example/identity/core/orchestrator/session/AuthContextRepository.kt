package com.example.identity.core.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface AuthContextRepository : JpaRepository<AuthContext, UUID> {
    fun findByAccountId(accountId: Long): List<AuthContext>

    /** Every [AuthContext] minted from this evidence trail, whose cached tokens go stale when it changes. */
    fun findByAuthEvidenceId(authEvidenceId: UUID): List<AuthContext>

    fun findByKeycloakSessionId(keycloakSessionId: String): List<AuthContext>
}
