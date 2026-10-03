package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.StepDataTypes
import com.example.identity.contract.tool_api.ToolModule
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonTypeInfo
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.databind.jsontype.NamedType

/**
 * How every [StepData] shape goes on the wire, set once here instead of on each shape: the `kind`
 * its module declared for it first, and an absent value as an absent key. The shapes stay plain
 * data classes in the module that produces them.
 */
@Configuration
class StepDataWireFormat {

    @Bean
    fun stepDataOnTheWire(
        toolModules: ObjectProvider<ToolModule>,
        stepDataTypes: ObjectProvider<StepDataTypes>,
    ) = JsonMapperBuilderCustomizer { builder ->
        val kinds = declaredKinds(toolModules.toList(), stepDataTypes.toList())
        builder.addMixIn(StepData::class.java, StepDataMixin::class.java)
        builder.registerSubtypes(*kinds.map { (kind, type) -> NamedType(type, kind) }.toTypedArray())
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = KIND)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private interface StepDataMixin

    companion object {
        /** The property that names the shape; also the discriminator in the API description. */
        const val KIND = "kind"

        /**
         * Every declared shape by its `kind`. A `kind` given twice, or one shape under two names,
         * would make the wire ambiguous, so either stops the start.
         */
        fun declaredKinds(toolModules: List<ToolModule>, stepDataTypes: List<StepDataTypes>): Map<String, Class<out StepData>> {
            val declarations = toolModules.flatMap { it.stepData.entries } + stepDataTypes.flatMap { it.types().entries }
            val byKind = declarations.groupBy({ it.key }, { it.value.type.java })
            val twice = byKind.filterValues { it.size > 1 }.keys
            check(twice.isEmpty()) { "StepData kind declared more than once: $twice" }
            val kinds = byKind.mapValues { it.value.single() }
            val renamed = kinds.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
            check(renamed.isEmpty()) { "StepData shape declared under several kinds: $renamed" }
            return kinds
        }
    }
}
