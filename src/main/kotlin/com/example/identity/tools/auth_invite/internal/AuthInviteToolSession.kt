package com.example.identity.tools.auth_invite.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** Tool session for toolId=auth-invite; only an existence marker for a single-step tool. */
@Entity
@Table(schema = "auth_invite", name = "invite_tool_session")
class AuthInviteToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: UUID? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}

interface AuthInviteToolSessionRepository : JpaRepository<AuthInviteToolSession, UUID> {
    @Modifying
    @Query("delete from AuthInviteToolSession e where e.createdAt < :cutoff")
    fun deleteByCreatedAtBefore(@Param("cutoff") cutoff: Instant): Int
}
