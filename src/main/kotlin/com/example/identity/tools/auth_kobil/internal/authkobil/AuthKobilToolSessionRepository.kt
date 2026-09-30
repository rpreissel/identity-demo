package com.example.identity.tools.auth_kobil.internal.authkobil

import com.example.identity.contract.tool_api.ids.ToolSessionId
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface AuthKobilToolSessionRepository : JpaRepository<AuthKobilToolSession, UUID> {
    fun findByToolSessionId(toolSessionId: ToolSessionId): AuthKobilToolSession?

    @Modifying
    @Query("delete from AuthKobilToolSession a where a.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
