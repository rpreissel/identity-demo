package com.example.identity.tools.auth_device.internal.enrolldevice

import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** Tool-session-scoped working data for toolId=enroll-device (docs/06-ablaeufe.md pattern). */
@Entity
@Table(schema = "auth_device", name = "enroll_tool_session")
class EnrollDeviceToolSession(
    @Id
    @Column(name = "tool_session_id", nullable = false)
    var toolSessionId: ToolSessionId? = null,
    createdAt: Instant
) {
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant? = createdAt
}
