package com.example.identity

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
// Fuer orchestrator.retention.ToolSessionRetentionProperties - die eine Stelle, an der die Aufbewahrungs-
// frist der Tool-Session-Daten steht (docs/07-betrieb.md #3).
@ConfigurationPropertiesScan
class IdentityApplication

/**
 * The [SCHEDULED_JOBS][com.example.identity.core.orchestrator.SCHEDULED_JOBS] run unless
 * `scheduling.enabled=false` - which only the test profile sets: otherwise every cached test
 * context sweeps its database on its own clock, in the middle of other tests. Tests call a job's
 * method directly when they mean to test it.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["scheduling.enabled"], havingValue = "true", matchIfMissing = true)
@EnableScheduling
class SchedulingConfig

fun main(args: Array<String>) {
    runApplication<IdentityApplication>(*args)
}
