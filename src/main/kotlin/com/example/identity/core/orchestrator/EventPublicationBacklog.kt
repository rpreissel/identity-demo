package com.example.identity.core.orchestrator

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * `identity.events.incomplete` (docs/07-betrieb.md Abschnitt 7): how many event publications are not
 * completed yet (ADR-29). A number that only grows means a listener fails for every event. Counted
 * when scraped, not per request.
 */
@Component
class EventPublicationBacklog(jdbcTemplate: JdbcTemplate, meterRegistry: MeterRegistry) {
    init {
        Gauge.builder("identity.events.incomplete") {
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM orchestrator.event_publication WHERE completion_date IS NULL",
                Long::class.java,
            )?.toDouble() ?: 0.0
        }
            .description("Event publications not yet completed by their listener")
            .register(meterRegistry)
    }
}
