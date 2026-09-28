package com.example.identity.contract.tool_api

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema
import kotlin.reflect.KClass

/**
 * What one step needs the client to show. Every shape is a declared type that names itself
 * through `kind` (docs/05-api.md #2). The shape belongs to the step, not the endpoint: a tool's
 * response often carries the next step's data, and `Step` pairs it with its `next`.
 * `kind` rather than `@t`, because the TypeScript generator mangles `@t` into `t`.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@Schema(
    description = "What the current step needs to render. `kind` names the shape; see the mapping " +
        "on this schema for the ones this deployment can produce."
)
interface StepData

/** The shape almost every tool step has: which inputs are still missing. Shared by many tools. */
@JsonTypeName("missing-fields")
@Schema(description = "Which inputs this step is still waiting for.")
data class MissingFields(
    @field:Schema(example = "[\"tan\"]")
    val missingFields: List<String>
) : StepData

/**
 * How a module tells the API description which [StepData] shapes it can produce. springdoc cannot
 * find them (they travel through `ToolOutcome`, not a controller signature), and a central
 * `@JsonSubTypes` list would have to name every module. `StepDataSchemaCustomizer` collects these
 * beans; `StepDataCoverageTest` reports a shape without one.
 */
fun interface StepDataTypes {
    fun types(): List<KClass<out StepData>>
}
