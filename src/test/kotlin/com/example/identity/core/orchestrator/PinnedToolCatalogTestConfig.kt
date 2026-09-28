package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/**
 * Pins the integration tests to the tool set of [StrategyTestFixtures.catalog], so candidate-list
 * assertions do not change when a module is added elsewhere. `@Primary` applies to the whole
 * context, so every orchestrator component sees this catalog.
 * [com.example.identity.core.orchestrator.tool.ToolCatalogStartStepTest] does not import it: it pins the
 * real catalog.
 */
@TestConfiguration
class PinnedToolCatalogTestConfig {
    @Bean
    @Primary
    fun pinnedToolHandlerRegistry(): ToolHandlerRegistry = StrategyTestFixtures.catalog
}
