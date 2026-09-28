package com.example.identity

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The one clock of the application (docs/08-projektrahmen.md Abschnitt 3). Every time rule reads
 * it instead of the system clock, so a test can set the time instead of waiting for it
 * (`ClockArchitectureTest`). In the root package, not in a module: `java.time.Clock` is the
 * contract, so injecting it adds no module dependency.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

    /** The JVM's default zone, which the local dates of the simulations read. */
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
