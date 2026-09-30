package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.SessionEvidenceId
import com.example.identity.contract.tool_api.ids.AccountId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface SessionEvidenceRecordRepository : JpaRepository<SessionEvidenceRecord, UUID> {
    fun findByAccountId(accountId: AccountId?): List<SessionEvidenceRecord>

    fun findBySessionEvidenceId(sessionEvidenceId: SessionEvidenceId): SessionEvidenceRecord?

    fun deleteAllBySessionEvidenceIdInBatch(ids: Collection<SessionEvidenceId>) = deleteAllByIdInBatch(ids.map { it.value })
}
