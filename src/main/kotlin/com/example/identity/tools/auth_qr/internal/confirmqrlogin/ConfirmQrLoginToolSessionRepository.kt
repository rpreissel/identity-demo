package com.example.identity.tools.auth_qr.internal.confirmqrlogin

import com.example.identity.contract.tool_api.ids.ToolSessionId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import java.time.Instant
import java.util.UUID

interface ConfirmQrLoginToolSessionRepository : JpaRepository<ConfirmQrLoginToolSession, UUID> {
    fun findByToolSessionId(toolSessionId: ToolSessionId): ConfirmQrLoginToolSession?

    @Modifying
    @Query("delete from ConfirmQrLoginToolSession e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
