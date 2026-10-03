package com.example.identity.contract.tool_api

import io.swagger.v3.oas.annotations.media.Schema
import kotlin.reflect.KClass

/**
 * What one step needs the client to show. Every shape is a declared type that names itself
 * through `kind` (docs/05-api.md #2). The shape belongs to the step, not the endpoint: a tool's
 * response often carries the next step's data, and `Step` pairs it with its `next`.
 *
 * A shape is a plain data class. The module that produces it declares it in a map: its `kind` as
 * the key, and with [stepData] what the API description says about it. How it goes on the wire is
 * set once, in the orchestrator.
 */
@Schema(
    description = "What the current step needs to render. `kind` names the shape; see the mapping " +
        "on this schema for the ones this deployment can produce."
)
interface StepData

/** The shape almost every tool step has: which inputs are still missing. Shared by many tools. */
data class MissingFields(
    val missingFields: List<String>
) : StepData

/**
 * One [StepData] shape as its module declares it for the API description: what it is for, and an
 * example value per property where a type alone says too little.
 */
class StepDataShape(
    val type: KClass<out StepData>,
    val description: String,
    val examples: Map<String, Any>,
)

/**
 * Declares the shape [S], as the value under its `kind`:
 * `"kobil-unlock" to stepData<KobilUnlockStep>("…", "unlockOptions" to listOf("biometric", "password"))`.
 */
inline fun <reified S : StepData> stepData(description: String, vararg examples: Pair<String, Any>): StepDataShape =
    StepDataShape(S::class, description, examples.toMap())

/**
 * How a module that is no tool module tells the API description which [StepData] shapes it can
 * produce (a tool module names them in its `toolModule(stepData = …)`). springdoc cannot find them:
 * they travel through `ToolOutcome`, not a controller signature, and a central list would have to
 * name every module.
 */
fun interface StepDataTypes {
    /** The shapes by their `kind`. */
    fun types(): Map<String, StepDataShape>
}
