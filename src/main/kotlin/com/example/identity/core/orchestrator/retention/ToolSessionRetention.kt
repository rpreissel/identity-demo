package com.example.identity.core.orchestrator.retention

import com.example.identity.contract.tool_api.retention.ToolSessionSweeper
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

/**
 * How long a tool session and its tool's working data may live (docs/07-betrieb.md #3). The one
 * place this data-protection period is stated, configurable because it is an operational decision.
 */
@ConfigurationProperties(prefix = "tool-session")
data class ToolSessionRetentionProperties(
    /** How long a tool session and its working data outlive their expiry; also the cutoff for [ToolSessionSweeper]s. */
    val retention: Duration = Duration.ofHours(24),
    /** How often the sweep runs. Far shorter than [retention] - a missed run must not leak data past it. */
    val sweepInterval: Duration = Duration.ofHours(1)
)

/**
 * The single schedule behind the modules' own sweeps of short-lived data ([ToolSessionSweeper]). Each
 * runs in its own try/catch, so one failing module does not keep the others' data past its retention.
 */
@Component
class ToolSessionRetentionDriver(
    private val sweepers: List<ToolSessionSweeper>,
    private val properties: ToolSessionRetentionProperties,
    private val clock: Clock
) {
    private val log = LoggerFactory.getLogger(ToolSessionRetentionDriver::class.java)

    @Scheduled(
        fixedDelayString = "\${tool-session.sweep-interval:PT1H}",
        initialDelayString = "\${tool-session.initial-sweep-delay:PT1M}"
    )
    fun sweep() {
        val cutoff = clock.instant().minus(properties.retention)
        sweepers.forEach { sweeper ->
            runCatching { sweeper.sweep(cutoff) }
                .onFailure { log.error("Tool-session sweep failed for {}", sweeper.javaClass.name, it) }
        }
    }
}
