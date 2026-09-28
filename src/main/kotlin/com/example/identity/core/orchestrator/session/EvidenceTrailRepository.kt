package com.example.identity.core.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface EvidenceTrailRepository : JpaRepository<EvidenceTrail, UUID> {
    fun findByAccountId(accountId: Long): List<EvidenceTrail>
}
