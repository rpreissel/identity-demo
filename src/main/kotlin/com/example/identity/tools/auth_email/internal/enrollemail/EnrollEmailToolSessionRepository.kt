package com.example.identity.tools.auth_email.internal.enrollemail

import com.example.identity.contract.tool_api.ids.ToolSessionId
import java.time.Instant
import java.util.UUID
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface EnrollEmailToolSessionRepository : JpaRepository<EnrollEmailToolSession, UUID> {
    fun findByToolSessionId(toolSessionId: ToolSessionId): EnrollEmailToolSession?

    @Modifying
    @Query("delete from EnrollEmailToolSession d where d.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
