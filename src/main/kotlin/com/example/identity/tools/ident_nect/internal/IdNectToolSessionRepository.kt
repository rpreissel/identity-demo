package com.example.identity.tools.ident_nect.internal

import com.example.identity.contract.tool_api.ids.ToolSessionId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface IdNectToolSessionRepository : JpaRepository<IdNectToolSession, UUID> {
    fun findByToolSessionId(toolSessionId: ToolSessionId): IdNectToolSession?

    @Modifying
    @Query("delete from IdNectToolSession e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
