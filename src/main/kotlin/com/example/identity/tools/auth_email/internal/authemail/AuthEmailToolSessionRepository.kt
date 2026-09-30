package com.example.identity.tools.auth_email.internal.authemail

import com.example.identity.contract.tool_api.ids.ToolSessionId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.repository.query.Param
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthEmailToolSessionRepository : JpaRepository<AuthEmailToolSession, UUID> {
    fun findByToolSessionId(toolSessionId: ToolSessionId): AuthEmailToolSession?

    @Modifying
    @Query("delete from AuthEmailToolSession e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
