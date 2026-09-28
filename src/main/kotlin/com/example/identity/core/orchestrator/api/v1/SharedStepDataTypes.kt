package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.StepDataTypes
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Registers the one [StepData][com.example.identity.contract.tool_api.StepData] shape `tool_api` owns itself,
 * [MissingFields], for the API description. `tool_api` holds no bean, so the collector's module
 * declares it.
 */
@Configuration
class SharedStepDataTypes {

    @Bean
    fun sharedStepDataShapes() = StepDataTypes { listOf(MissingFields::class) }
}
