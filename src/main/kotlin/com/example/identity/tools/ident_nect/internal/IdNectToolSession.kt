package com.example.identity.tools.ident_nect.internal

import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Tool-session-scoped working data for toolId=ident-nect: the Nect case this run waits for, and
 * where Nect sends the user back to. [returnUri] is null for the app channel (`/app/`).
 */
@Entity
@Table(schema = "ident_nect", name = "ident_tool_session")
class IdNectToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: ToolSessionId? = null,

    @Column(name = "case_id")
    var caseId: UUID? = null,

    @Column(name = "return_uri", length = 2048)
    var returnUri: String? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
