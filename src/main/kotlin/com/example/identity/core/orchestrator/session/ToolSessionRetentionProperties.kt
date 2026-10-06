package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.retention.ToolSessionSweeper
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * How long a tool session and its tool's working data may live (docs/07-betrieb.md #3). The one
 * place this data-protection period is stated, configurable because it is an operational decision.
 * Read by the sweep (`RetentionJob`, `ToolSessionRetentionDriver`) and by the keys the data lies
 * under ([RetentionClassKeys]), so a key never retires before its last row is swept.
 */
@ConfigurationProperties(prefix = "tool-session")
data class ToolSessionRetentionProperties(
    /** How long a tool session and its working data outlive their expiry; also the cutoff for [ToolSessionSweeper]s. */
    val retention: Duration = Duration.ofHours(24),
    /** How often the sweep runs. Far shorter than [retention] - a missed run must not leak data past it. */
    val sweepInterval: Duration = Duration.ofHours(1)
)

/** How long a journey, and with it its tool sessions, outlives its end (docs/07-betrieb.md #3). */
val JOURNEY_RETENTION: Duration = Duration.ofDays(7)
