package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.domain.ToolCatalog
import com.example.identity.core.orchestrator.domain.policy.AuthPolicy
import com.example.identity.core.orchestrator.domain.policy.DefaultAuthPolicy
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration

/**
 * Wires the domain's rule objects into Spring, so the domain itself carries no framework
 * annotation (docs/adr/ADR-040-fachkern-im-paket-domain.md). The journeys need no wiring: they
 * are objects, listed in `Journeys`.
 */
@Configuration(proxyBeanMethods = false)
class DomainBeans {

    @Bean
    fun authPolicy(
        catalog: ToolCatalog,
        clock: Clock,
        @Value("\${identity.policy.loa2-max-age:PT30M}") loa2MaxAge: Duration,
        @Value("\${identity.policy.self-service-max-age:PT5M}") selfServiceMaxAge: Duration,
    ): AuthPolicy = DefaultAuthPolicy(catalog, clock, loa2MaxAge, selfServiceMaxAge)
}
