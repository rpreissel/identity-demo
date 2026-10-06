package com.example.identity.core.orchestrator.retention

import com.example.identity.contract.tool_api.retention.ToolSessionSweeper
import com.example.identity.core.orchestrator.session.ToolSessionRetentionProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock

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
