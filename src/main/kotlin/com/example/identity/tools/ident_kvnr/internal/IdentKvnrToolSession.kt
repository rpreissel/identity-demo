package com.example.identity.tools.ident_kvnr.internal

import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Tool-session-scoped working data for toolId=ident-kvnr: what was typed, kept across a reload. */
@Entity
@Table(schema = "ident_kvnr", name = "ident_tool_session")
class IdentKvnrToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: ToolSessionId? = null,

    var kvnr: String? = null,

    var partnerNumber: String? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
